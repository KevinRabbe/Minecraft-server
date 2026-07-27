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
 * Read-only Coin supply/movement analytics derived from authoritative operation/economic evidence.
 *
 * <p>Wallet, Bank, market, trade, commission, and clan-treasury custody do not all balance inside one ledger
 * operation: some durable escrows debit now and credit later. Supply therefore must never be inferred from raw ledger
 * net alone. This projection classifies the stable V1 Coin-bearing operation types explicitly, derives the special
 * Bazaar fee sink from immutable fill evidence, and exposes every unknown/malformed Coin-bearing operation as
 * unclassified rather than guessing its supply effect.</p>
 */
public final class CoinFlowAnalyticsRepository {
    private static final int MAX_REASON_ROWS = 500;
    private static final String MIXED_REASON = "<mixed>";
    private static final String BAZAAR_FEE_REASON = "bazaar.match.fee";

    private static final String CLASSIFIED_OPERATIONS_CTE = """
            WITH params AS (
                SELECT ?::TIMESTAMPTZ AS window_start,
                       ?::TIMESTAMPTZ AS observed_end
            ), ledger_ops AS (
                SELECT ledger.operation_id,
                       MIN(ledger.created_at) AS ledger_at,
                       CASE
                           WHEN COUNT(DISTINCT ledger.reason) = 1 THEN MIN(ledger.reason)
                           ELSE '<mixed>'
                       END AS ledger_reason,
                       SUM(CASE ledger.direction
                           WHEN 'CREDIT' THEN ledger.amount::NUMERIC
                           ELSE -ledger.amount::NUMERIC
                       END) AS ledger_net_minor,
                       SUM(ledger.amount::NUMERIC) AS gross_minor
                FROM economic_ledger ledger
                CROSS JOIN params
                WHERE ledger.asset_type = ?
                  AND ledger.asset_id = ?
                  AND ledger.created_at >= params.window_start
                  AND ledger.created_at < params.observed_end
                GROUP BY ledger.operation_id
            ), bazaar_fees AS (
                SELECT fill.match_operation_id AS operation_id,
                       SUM(fill.fee_minor::NUMERIC) AS fee_minor
                FROM bazaar_fills fill
                GROUP BY fill.match_operation_id
            ), salvage_returns AS (
                SELECT salvage.operation_id,
                       salvage.coin_return_minor::NUMERIC AS coin_return_minor
                FROM salvage_records salvage
            ), processed AS (
                SELECT operation.operation_id,
                       operation.operation_type,
                       operation.completed_at,
                       operation.result,
                       ledger.ledger_reason,
                       COALESCE(ledger.ledger_net_minor, 0::NUMERIC) AS ledger_net_minor,
                       COALESCE(ledger.gross_minor, 0::NUMERIC) AS gross_minor,
                       ledger.operation_id IS NOT NULL AS has_coin_ledger,
                       COALESCE(bazaar.fee_minor, 0::NUMERIC) AS bazaar_fill_fee_minor,
                       CASE
                           WHEN operation.operation_type IN ('COIN_SYSTEM_CREDIT', 'COIN_SYSTEM_DEBIT')
                                AND COALESCE(operation.result ->> 'amount_minor', '') ~ '^[0-9]+$'
                               THEN (operation.result ->> 'amount_minor')::NUMERIC
                           WHEN operation.operation_type = 'BANK_TIER_UPGRADE'
                                AND COALESCE(operation.result ->> 'cost_minor', '') ~ '^[0-9]+$'
                               THEN (operation.result ->> 'cost_minor')::NUMERIC
                           WHEN operation.operation_type = 'BANK_INTEREST_CREDIT'
                                AND COALESCE(operation.result ->> 'credited_minor', '') ~ '^[0-9]+$'
                               THEN (operation.result ->> 'credited_minor')::NUMERIC
                           WHEN operation.operation_type = 'BOUNTY_CONTRACT_START'
                                AND COALESCE(operation.result ->> 'fee_minor', '') ~ '^[0-9]+$'
                               THEN (operation.result ->> 'fee_minor')::NUMERIC
                           WHEN operation.operation_type = 'UNIQUE_ITEM_SALVAGE'
                               THEN salvage.coin_return_minor
                           WHEN operation.operation_type = 'BAZAAR_MATCH'
                                AND COALESCE(operation.result ->> 'fees_destroyed_minor', '') ~ '^[0-9]+$'
                               THEN (operation.result ->> 'fees_destroyed_minor')::NUMERIC
                           ELSE NULL
                       END AS expected_amount_minor
                FROM processed_operations operation
                CROSS JOIN params
                LEFT JOIN ledger_ops ledger ON ledger.operation_id = operation.operation_id
                LEFT JOIN bazaar_fees bazaar ON bazaar.operation_id = operation.operation_id
                LEFT JOIN salvage_returns salvage ON salvage.operation_id = operation.operation_id
                WHERE operation.completed_at >= params.window_start
                  AND operation.completed_at < params.observed_end
            ), candidates AS (
                SELECT processed.operation_id,
                       processed.operation_type,
                       COALESCE(
                           processed.ledger_reason,
                           CASE WHEN processed.operation_type = 'BAZAAR_MATCH' THEN 'bazaar.match.fee' END
                       ) AS operation_reason,
                       processed.ledger_net_minor,
                       processed.gross_minor,
                       processed.bazaar_fill_fee_minor,
                       processed.expected_amount_minor,
                       CASE
                           WHEN processed.operation_type = 'COIN_SYSTEM_CREDIT'
                               THEN processed.expected_amount_minor
                           WHEN processed.operation_type = 'COIN_SYSTEM_DEBIT'
                               THEN -processed.expected_amount_minor
                           WHEN processed.operation_type = 'BANK_INTEREST_CREDIT'
                               THEN processed.expected_amount_minor
                           WHEN processed.operation_type = 'BANK_TIER_UPGRADE'
                               THEN -processed.expected_amount_minor
                           WHEN processed.operation_type = 'BOUNTY_CONTRACT_START'
                               THEN -processed.expected_amount_minor
                           WHEN processed.operation_type = 'UNIQUE_ITEM_SALVAGE'
                               THEN processed.expected_amount_minor
                           WHEN processed.operation_type = 'BAZAAR_MATCH'
                               THEN -processed.expected_amount_minor
                           WHEN processed.operation_type IN (
                               'COIN_PLAYER_TRANSFER',
                               'BANK_DEPOSIT',
                               'BANK_WITHDRAW',
                               'BAZAAR_BUY_ORDER_CREATE',
                               'BAZAAR_ORDER_CANCEL',
                               'AUCTION_LISTING_PURCHASE',
                               'SECURE_TRADE_COIN_OFFER',
                               'SECURE_TRADE_SETTLE',
                               'SECURE_TRADE_CANCEL',
                               'CRAFTING_COMMISSION_CREATE',
                               'CRAFTING_COMMISSION_CANCEL',
                               'CRAFTING_COMMISSION_COMPLETE',
                               'CLAN_TREASURY_DEPOSIT',
                               'CLAN_TREASURY_WITHDRAW'
                           ) THEN 0::NUMERIC
                           ELSE NULL
                       END AS supply_change_minor,
                       CASE
                           WHEN processed.operation_type IN (
                               'COIN_PLAYER_TRANSFER',
                               'BANK_DEPOSIT',
                               'BANK_WITHDRAW',
                               'BAZAAR_BUY_ORDER_CREATE',
                               'BAZAAR_ORDER_CANCEL',
                               'AUCTION_LISTING_PURCHASE',
                               'SECURE_TRADE_COIN_OFFER',
                               'SECURE_TRADE_SETTLE',
                               'SECURE_TRADE_CANCEL',
                               'CRAFTING_COMMISSION_CREATE',
                               'CRAFTING_COMMISSION_CANCEL',
                               'CRAFTING_COMMISSION_COMPLETE',
                               'CLAN_TREASURY_DEPOSIT',
                               'CLAN_TREASURY_WITHDRAW'
                           ) THEN TRUE
                           WHEN processed.operation_type = 'BAZAAR_MATCH'
                               THEN processed.expected_amount_minor IS NOT NULL
                                AND processed.expected_amount_minor = processed.bazaar_fill_fee_minor
                           WHEN processed.operation_type IN (
                               'COIN_SYSTEM_CREDIT',
                               'BANK_INTEREST_CREDIT',
                               'UNIQUE_ITEM_SALVAGE'
                           ) THEN processed.expected_amount_minor IS NOT NULL
                                AND processed.ledger_net_minor = processed.expected_amount_minor
                           WHEN processed.operation_type IN (
                               'COIN_SYSTEM_DEBIT',
                               'BANK_TIER_UPGRADE',
                               'BOUNTY_CONTRACT_START'
                           ) THEN processed.expected_amount_minor IS NOT NULL
                                AND processed.ledger_net_minor = -processed.expected_amount_minor
                           ELSE FALSE
                       END AS classified
                FROM processed
                WHERE processed.has_coin_ledger
                   OR COALESCE(processed.expected_amount_minor, 0::NUMERIC) <> 0::NUMERIC
                UNION ALL
                SELECT ledger.operation_id,
                       NULL AS operation_type,
                       ledger.ledger_reason AS operation_reason,
                       ledger.ledger_net_minor,
                       ledger.gross_minor,
                       0::NUMERIC AS bazaar_fill_fee_minor,
                       NULL::NUMERIC AS expected_amount_minor,
                       NULL::NUMERIC AS supply_change_minor,
                       FALSE AS classified
                FROM ledger_ops ledger
                LEFT JOIN processed_operations operation ON operation.operation_id = ledger.operation_id
                WHERE operation.operation_id IS NULL
            )
            """;

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
                        totals.confirmedCreatedMinor(),
                        totals.confirmedDestroyedMinor(),
                        totals.confirmedNetSupplyChangeMinor(),
                        totals.grossMovementMinor(),
                        totals.classifiedOperationCount(),
                        totals.unclassifiedOperationCount(),
                        totals.unclassifiedGrossMovementMinor(),
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
        String sql = CLASSIFIED_OPERATIONS_CTE + """
                SELECT COALESCE(SUM(GREATEST(supply_change_minor, 0::NUMERIC)) FILTER (WHERE classified), 0::NUMERIC)
                           AS confirmed_created_minor,
                       COALESCE(SUM(GREATEST(-supply_change_minor, 0::NUMERIC)) FILTER (WHERE classified), 0::NUMERIC)
                           AS confirmed_destroyed_minor,
                       COALESCE(SUM(supply_change_minor) FILTER (WHERE classified), 0::NUMERIC)
                           AS confirmed_net_supply_change_minor,
                       COALESCE(SUM(gross_minor), 0::NUMERIC) AS gross_movement_minor,
                       COUNT(*) FILTER (WHERE classified)::BIGINT AS classified_operation_count,
                       COUNT(*) FILTER (WHERE NOT classified)::BIGINT AS unclassified_operation_count,
                       COALESCE(SUM(gross_minor) FILTER (WHERE NOT classified), 0::NUMERIC)
                           AS unclassified_gross_movement_minor
                FROM candidates
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCommon(statement, windowStart, observedThrough);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Coin flow totals query returned no summary row");
                }
                return new Totals(
                        exactInteger(row, "confirmed_created_minor"),
                        exactInteger(row, "confirmed_destroyed_minor"),
                        exactInteger(row, "confirmed_net_supply_change_minor"),
                        exactInteger(row, "gross_movement_minor"),
                        row.getLong("classified_operation_count"),
                        row.getLong("unclassified_operation_count"),
                        exactInteger(row, "unclassified_gross_movement_minor")
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
        String sql = CLASSIFIED_OPERATIONS_CTE + """
                , reason_summary AS (
                    SELECT operation_reason,
                           SUM(GREATEST(supply_change_minor, 0::NUMERIC)) AS created_minor,
                           SUM(GREATEST(-supply_change_minor, 0::NUMERIC)) AS destroyed_minor,
                           SUM(supply_change_minor) AS net_supply_change_minor,
                           SUM(gross_minor) AS gross_movement_minor,
                           COUNT(*)::BIGINT AS operation_count
                    FROM candidates
                    WHERE classified
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
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCommon(statement, windowStart, observedThrough);
            statement.setInt(5, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<CoinFlowReasonSummary> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new CoinFlowReasonSummary(
                            Objects.requireNonNullElse(rows.getString("operation_reason"), MIXED_REASON),
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

    private static void bindCommon(
            PreparedStatement statement,
            Instant windowStart,
            Instant observedThrough
    ) throws SQLException {
        statement.setTimestamp(1, Timestamp.from(windowStart));
        statement.setTimestamp(2, Timestamp.from(observedThrough));
        statement.setString(3, CoinCurrency.LEDGER_ASSET_TYPE);
        statement.setString(4, CoinCurrency.LEDGER_ASSET_ID);
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
            BigInteger confirmedCreatedMinor,
            BigInteger confirmedDestroyedMinor,
            BigInteger confirmedNetSupplyChangeMinor,
            BigInteger grossMovementMinor,
            long classifiedOperationCount,
            long unclassifiedOperationCount,
            BigInteger unclassifiedGrossMovementMinor
    ) {
    }
}
