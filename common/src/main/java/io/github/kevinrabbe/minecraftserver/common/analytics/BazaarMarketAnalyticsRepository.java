package io.github.kevinrabbe.minecraftserver.common.analytics;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Read-only Bazaar market projection over authoritative order state and append-only matcher results.
 * No analytics event, price cache, order copy, or secondary market authority is written.
 */
public final class BazaarMarketAnalyticsRepository {
    private static final String MATCH_OPERATION = "BAZAAR_MATCH";
    private static final Pattern DEFINITION_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final DataSource dataSource;
    private final Clock clock;

    public BazaarMarketAnalyticsRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public BazaarMarketAnalyticsRepository(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BazaarMarketSummary summarize(
            String commodityDefinitionId,
            Instant historyWindowStart,
            Instant historyWindowEnd
    ) throws SQLException {
        String commodity = requireDefinitionId(commodityDefinitionId);
        Objects.requireNonNull(historyWindowStart, "historyWindowStart");
        Objects.requireNonNull(historyWindowEnd, "historyWindowEnd");
        if (!historyWindowEnd.isAfter(historyWindowStart)) {
            throw new IllegalArgumentException("historyWindowEnd must be after historyWindowStart");
        }

        Instant snapshotAt = clock.instant();
        Instant observedThrough = observedThrough(historyWindowStart, historyWindowEnd, snapshotAt);

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                BookState book = loadBook(connection, commodity);
                ExecutionHistory history = loadHistory(connection, commodity, historyWindowStart, observedThrough);
                connection.commit();
                return new BazaarMarketSummary(
                        commodity,
                        snapshotAt,
                        historyWindowStart,
                        historyWindowEnd,
                        observedThrough,
                        book.bestBidPriceMinor(),
                        book.bestAskPriceMinor(),
                        book.bestBidQuantity(),
                        book.bestAskQuantity(),
                        book.openBuyOrderCount(),
                        book.openSellOrderCount(),
                        book.openBuyQuantity(),
                        book.openSellQuantity(),
                        history.matchPassCount(),
                        history.fillCount(),
                        history.filledQuantity(),
                        history.grossTradeValueMinor(),
                        history.feesDestroyedMinor()
                );
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static BookState loadBook(Connection connection, String commodity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH open_orders AS (
                    SELECT side, limit_price_minor, remaining_quantity
                    FROM bazaar_orders
                    WHERE commodity_definition_id = ?
                      AND status = 'OPEN'
                ), best AS (
                    SELECT MAX(limit_price_minor) FILTER (WHERE side = 'BUY') AS best_bid_price_minor,
                           MIN(limit_price_minor) FILTER (WHERE side = 'SELL') AS best_ask_price_minor
                    FROM open_orders
                )
                SELECT best_bid_price_minor,
                       best_ask_price_minor,
                       COALESCE((
                           SELECT SUM(remaining_quantity::NUMERIC)
                           FROM open_orders, best
                           WHERE side = 'BUY' AND limit_price_minor = best.best_bid_price_minor
                       ), 0::NUMERIC) AS best_bid_quantity,
                       COALESCE((
                           SELECT SUM(remaining_quantity::NUMERIC)
                           FROM open_orders, best
                           WHERE side = 'SELL' AND limit_price_minor = best.best_ask_price_minor
                       ), 0::NUMERIC) AS best_ask_quantity,
                       (SELECT COUNT(*)::BIGINT FROM open_orders WHERE side = 'BUY') AS open_buy_order_count,
                       (SELECT COUNT(*)::BIGINT FROM open_orders WHERE side = 'SELL') AS open_sell_order_count,
                       COALESCE((
                           SELECT SUM(remaining_quantity::NUMERIC) FROM open_orders WHERE side = 'BUY'
                       ), 0::NUMERIC) AS open_buy_quantity,
                       COALESCE((
                           SELECT SUM(remaining_quantity::NUMERIC) FROM open_orders WHERE side = 'SELL'
                       ), 0::NUMERIC) AS open_sell_quantity
                FROM best
                """)) {
            statement.setString(1, commodity);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Bazaar book analytics query returned no summary row");
                }
                return new BookState(
                        nullableLong(row, "best_bid_price_minor"),
                        nullableLong(row, "best_ask_price_minor"),
                        exactInteger(row, "best_bid_quantity"),
                        exactInteger(row, "best_ask_quantity"),
                        row.getLong("open_buy_order_count"),
                        row.getLong("open_sell_order_count"),
                        exactInteger(row, "open_buy_quantity"),
                        exactInteger(row, "open_sell_quantity")
                );
            }
        }
    }

    private static ExecutionHistory loadHistory(
            Connection connection,
            String commodity,
            Instant historyWindowStart,
            Instant observedThrough
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)::BIGINT AS match_pass_count,
                       COALESCE(SUM((result ->> 'fills')::NUMERIC), 0::NUMERIC) AS fill_count,
                       COALESCE(SUM((result ->> 'quantity_filled')::NUMERIC), 0::NUMERIC) AS filled_quantity,
                       COALESCE(SUM((result ->> 'gross_trade_value_minor')::NUMERIC), 0::NUMERIC)
                           AS gross_trade_value_minor,
                       COALESCE(SUM((result ->> 'fees_destroyed_minor')::NUMERIC), 0::NUMERIC)
                           AS fees_destroyed_minor
                FROM processed_operations
                WHERE operation_type = ?
                  AND result ->> 'commodity_definition_id' = ?
                  AND completed_at >= ?
                  AND completed_at < ?
                """)) {
            statement.setString(1, MATCH_OPERATION);
            statement.setString(2, commodity);
            statement.setTimestamp(3, Timestamp.from(historyWindowStart));
            statement.setTimestamp(4, Timestamp.from(observedThrough));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Bazaar execution analytics query returned no summary row");
                }
                BigInteger fillCount = exactInteger(row, "fill_count");
                return new ExecutionHistory(
                        row.getLong("match_pass_count"),
                        longExact(fillCount, "fill_count"),
                        exactInteger(row, "filled_quantity"),
                        exactInteger(row, "gross_trade_value_minor"),
                        exactInteger(row, "fees_destroyed_minor")
                );
            }
        }
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static BigInteger exactInteger(ResultSet row, String column) throws SQLException {
        BigDecimal value = row.getBigDecimal(column);
        if (value == null) {
            throw new SQLException("Bazaar analytics query returned null numeric column: " + column);
        }
        try {
            return value.toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new SQLException("Bazaar analytics query returned non-integral value for " + column, exception);
        }
    }

    private static long longExact(BigInteger value, String name) throws SQLException {
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw new SQLException("Bazaar analytics " + name + " exceeds BIGINT projection range", exception);
        }
    }

    private static Instant observedThrough(Instant windowStart, Instant windowEnd, Instant now) {
        if (now.isBefore(windowStart)) {
            return windowStart;
        }
        if (now.isAfter(windowEnd)) {
            return windowEnd;
        }
        return now;
    }

    private static String requireDefinitionId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        String normalized = value.trim();
        if (!DEFINITION_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("commodityDefinitionId has invalid definition-id syntax: " + normalized);
        }
        return normalized;
    }

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private record BookState(
            Long bestBidPriceMinor,
            Long bestAskPriceMinor,
            BigInteger bestBidQuantity,
            BigInteger bestAskQuantity,
            long openBuyOrderCount,
            long openSellOrderCount,
            BigInteger openBuyQuantity,
            BigInteger openSellQuantity
    ) {
    }

    private record ExecutionHistory(
            long matchPassCount,
            long fillCount,
            BigInteger filledQuantity,
            BigInteger grossTradeValueMinor,
            BigInteger feesDestroyedMinor
    ) {
    }
}
