package io.github.kevinrabbe.minecraftserver.common.analytics;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only Coin supply/movement analytics derived from append-only economic ledger evidence.
 *
 * <p>Coin lines are first netted by operation. Balanced internal custody/player movements therefore contribute gross
 * movement but zero supply change, while true faucets/sinks remain positive/negative operation net. No analytics
 * event or secondary balance is written.</p>
 */
public final class CoinFlowAnalyticsRepository {
    private static final int MAX_REASON_ROWS = 500;
    private static final String MIXED_REASON = "<mixed>";

    private final DataSource dataSource;
    private final Clock clock;

    public CoinFlowAnalyticsRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public CoinFlowAnalyticsRepository(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CoinFlowSummary summarize(Instant windowStart, Instant windowEnd, int maxReasons) throws SQLException {
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("windowEnd must be after windowStart");
        }
        if (maxReasons < 1 || maxReasons > MAX_REASON_ROWS) {
            throw new IllegalArgumentException("maxReasons must be between 1 and " + MAX_REASON_ROWS);
        }

        Instant observedThrough = observedThrough(windowStart, windowEnd, clock.instant());

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                Totals totals = loadTotals(connection, windowStart, observedThrough);
                List<CoinFlowReasonSummary> loadedReasons = loadReasons(
                        connection,
                        windowStart,
                        observedThrough,
                        maxReasons + 1
                );
                boolean truncated = loadedReasons.size() > maxReasons;
                List<CoinFlowReasonSummary> reasons = truncated
                        ? List.copyOf(loadedReasons.subList(0, maxReasons))
                        : List.copyOf(loadedReasons);
                connection.commit();
                return new CoinFlowSummary(
                        windowStart,
                        windowEnd,
                        observedThrough,
                        totals.createdMinor(),
                        totals.destroyedMinor(),
                        totals.netSupplyChangeMinor(),
                        totals.grossMovementMinor(),
                        totals.operationCount(),
                        reasons,
                        truncated
                );
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static Totals loadTotals(
            Connection connection,
            Instant windowStart,
            Instant observedThrough
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH operations AS (
                    SELECT operation_id,
                           SUM(CASE direction
                               WHEN 'CREDIT' THEN amount::NUMERIC
                               ELSE -amount::NUMERIC
                           END) AS net_minor,
                           SUM(amount::NUMERIC) AS gross_minor
                    FROM economic_ledger
                    WHERE asset_type = ?
                      AND asset_id = ?
                      AND created_at >= ?
                      AND created_at < ?
                    GROUP BY operation_id
                )
                SELECT COALESCE(SUM(GREATEST(net_minor, 0::NUMERIC)), 0::NUMERIC) AS created_minor,
                       COALESCE(SUM(GREATEST(-net_minor, 0::NUMERIC)), 0::NUMERIC) AS destroyed_minor,
                       COALESCE(SUM(net_minor), 0::NUMERIC) AS net_supply_change_minor,
                       COALESCE(SUM(gross_minor), 0::NUMERIC) AS gross_movement_minor,
                       COUNT(*)::BIGINT AS operation_count
                FROM operations
                """)) {
            bindWindow(statement, windowStart, observedThrough);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Coin flow totals query returned no summary row");
                }
                return new Totals(
                        exactInteger(row, "created_minor"),
                        exactInteger(row, "destroyed_minor"),
                        exactInteger(row, "net_supply_change_minor"),
                        exactInteger(row, "gross_movement_minor"),
                        row.getLong("operation_count")
                );
            }
        }
    }

    private static List<CoinFlowReasonSummary> loadReasons(
            Connection connection,
            Instant windowStart,
            Instant observedThrough,
            int limit
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH operations AS (
                    SELECT operation_id,
                           CASE
                               WHEN COUNT(DISTINCT reason) = 1 THEN MIN(reason)
                               ELSE ?
                           END AS operation_reason,
                           SUM(CASE direction
                               WHEN 'CREDIT' THEN amount::NUMERIC
                               ELSE -amount::NUMERIC
                           END) AS net_minor,
                           SUM(amount::NUMERIC) AS gross_minor
                    FROM economic_ledger
                    WHERE asset_type = ?
                      AND asset_id = ?
                      AND created_at >= ?
                      AND created_at < ?
                    GROUP BY operation_id
                ), reason_summary AS (
                    SELECT operation_reason,
                           SUM(GREATEST(net_minor, 0::NUMERIC)) AS created_minor,
                           SUM(GREATEST(-net_minor, 0::NUMERIC)) AS destroyed_minor,
                           SUM(net_minor) AS net_supply_change_minor,
                           SUM(gross_minor) AS gross_movement_minor,
                           COUNT(*)::BIGINT AS operation_count
                    FROM operations
                    GROUP BY operation_reason
                )
                SELECT operation_reason,
                       created_minor,
                       destroyed_minor,
                       net_supply_change_minor,
                       gross_movement_minor,
                       operation_count
                FROM reason_summary
                ORDER BY (created_minor + destroyed_minor) DESC,
                         gross_movement_minor DESC,
                         operation_reason
                LIMIT ?
                """)) {
            statement.setString(1, MIXED_REASON);
            statement.setString(2, CoinCurrency.LEDGER_ASSET_TYPE);
            statement.setString(3, CoinCurrency.LEDGER_ASSET_ID);
            statement.setTimestamp(4, Timestamp.from(windowStart));
            statement.setTimestamp(5, Timestamp.from(observedThrough));
            statement.setInt(6, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<CoinFlowReasonSummary> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new CoinFlowReasonSummary(
                            rows.getString("operation_reason"),
                            exactInteger(rows, "created_minor"),
                            exactInteger(rows, "destroyed_minor"),
                            exactInteger(rows, "net_supply_change_minor"),
                            exactInteger(rows, "gross_movement_minor"),
                            rows.getLong("operation_count")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static void bindWindow(
            PreparedStatement statement,
            Instant windowStart,
            Instant observedThrough
    ) throws SQLException {
        statement.setString(1, CoinCurrency.LEDGER_ASSET_TYPE);
        statement.setString(2, CoinCurrency.LEDGER_ASSET_ID);
        statement.setTimestamp(3, Timestamp.from(windowStart));
        statement.setTimestamp(4, Timestamp.from(observedThrough));
    }

    private static BigInteger exactInteger(ResultSet row, String column) throws SQLException {
        BigDecimal value = row.getBigDecimal(column);
        if (value == null) {
            throw new SQLException("Coin flow query returned null numeric column: " + column);
        }
        try {
            return value.toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new SQLException("Coin flow query returned non-integral value for " + column, exception);
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

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private record Totals(
            BigInteger createdMinor,
            BigInteger destroyedMinor,
            BigInteger netSupplyChangeMinor,
            BigInteger grossMovementMinor,
            long operationCount
    ) {
    }
}
