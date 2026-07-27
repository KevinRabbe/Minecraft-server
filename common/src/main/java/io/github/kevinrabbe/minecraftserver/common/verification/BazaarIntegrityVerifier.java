package io.github.kevinrabbe.minecraftserver.common.verification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only bounded reconciliation of Bazaar order, fill, delivery, cancellation, and match-pass evidence. */
public final class BazaarIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String BUY_CREATE = "BAZAAR_BUY_ORDER_CREATE";
    private static final String SELL_CREATE = "BAZAAR_SELL_ORDER_CREATE";
    private static final String MATCH = "BAZAAR_MATCH";
    private static final String CANCEL = "BAZAAR_ORDER_CANCEL";

    private final DataSource dataSource;

    public BazaarIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyCreateEvidence(connection, issues, maxIssues);
            verifyFillDeliveryEvidence(connection, issues, maxIssues);
            verifyOrderStateFromFills(connection, issues, maxIssues);
            verifyCancellationEvidence(connection, issues, maxIssues);
            verifyMatchPassShape(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyCreateEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT orders.order_id, orders.create_operation_id
                FROM bazaar_orders orders
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = orders.create_operation_id
                WHERE operation.operation_id IS NULL
                   OR operation.operation_type IS DISTINCT FROM CASE orders.side
                        WHEN 'BUY' THEN ?
                        WHEN 'SELL' THEN ?
                        ELSE NULL
                      END
                   OR operation.result ->> 'order_id' IS DISTINCT FROM orders.order_id::TEXT
                   OR operation.result ->> 'player_id' IS DISTINCT FROM orders.player_id::TEXT
                   OR operation.result ->> 'commodity_definition_id' IS DISTINCT FROM orders.commodity_definition_id
                   OR operation.result ->> 'side' IS DISTINCT FROM orders.side
                   OR operation.result ->> 'limit_price_minor' IS DISTINCT FROM orders.limit_price_minor::TEXT
                   OR operation.result ->> 'original_quantity' IS DISTINCT FROM orders.original_quantity::TEXT
                   OR operation.result ->> 'remaining_quantity' IS DISTINCT FROM orders.original_quantity::TEXT
                   OR operation.result ->> 'status' IS DISTINCT FROM 'OPEN'
                   OR operation.result ->> 'reason' IS NULL
                   OR operation.result ->> 'reason' = ''
                   OR (
                        orders.side = 'BUY'
                        AND operation.result ->> 'reserved_money_minor'
                            IS DISTINCT FROM (orders.original_quantity::NUMERIC * orders.limit_price_minor::NUMERIC)::TEXT
                      )
                   OR (
                        orders.side = 'SELL'
                        AND operation.result ->> 'reserved_money_minor' IS DISTINCT FROM '0'
                      )
                   OR (
                        orders.side = 'BUY'
                        AND (
                            SELECT COUNT(*)
                            FROM economic_ledger ledger
                            WHERE ledger.operation_id = orders.create_operation_id
                              AND ledger.player_id = orders.player_id
                              AND ledger.asset_type = 'CURRENCY'
                              AND ledger.asset_id = 'coin'
                              AND ledger.amount::NUMERIC
                                  = orders.original_quantity::NUMERIC * orders.limit_price_minor::NUMERIC
                              AND ledger.direction = 'DEBIT'
                              AND ledger.reason = operation.result ->> 'reason'
                        ) <> 1
                      )
                   OR (
                        orders.side = 'BUY'
                        AND (SELECT COUNT(*) FROM economic_ledger ledger
                             WHERE ledger.operation_id = orders.create_operation_id) <> 1
                      )
                   OR (
                        orders.side = 'SELL'
                        AND (SELECT COUNT(*) FROM economic_ledger ledger
                             WHERE ledger.operation_id = orders.create_operation_id) <> 0
                      )
                ORDER BY orders.order_id
                LIMIT ?
                """)) {
            statement.setString(1, BUY_CREATE);
            statement.setString(2, SELL_CREATE);
            statement.setInt(3, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID orderId = rows.getObject("order_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BAZAAR_CREATE_EVIDENCE_MISMATCH",
                            orderId.toString(),
                            "Bazaar order creation does not reconcile with its processed operation and initial escrow evidence"
                    ));
                }
            }
        }
    }

    private static void verifyFillDeliveryEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT fill.fill_id
                FROM bazaar_fills fill
                JOIN bazaar_orders buy ON buy.order_id = fill.buy_order_id
                JOIN bazaar_orders sell ON sell.order_id = fill.sell_order_id
                WHERE buy.side IS DISTINCT FROM 'BUY'
                   OR sell.side IS DISTINCT FROM 'SELL'
                   OR buy.commodity_definition_id IS DISTINCT FROM sell.commodity_definition_id
                   OR fill.execution_price_minor > buy.limit_price_minor
                   OR fill.execution_price_minor < sell.limit_price_minor
                   OR fill.fee_minor::NUMERIC
                        > fill.quantity::NUMERIC * fill.execution_price_minor::NUMERIC
                   OR (
                        SELECT COUNT(*)
                        FROM pending_commodity_deliveries delivery
                        WHERE delivery.source_operation_id = fill.fill_operation_id
                          AND delivery.player_id = buy.player_id
                          AND delivery.commodity_definition_id = buy.commodity_definition_id
                          AND delivery.quantity = fill.quantity
                      ) <> 1
                   OR (
                        SELECT COUNT(*)
                        FROM pending_commodity_deliveries delivery
                        WHERE delivery.source_operation_id = fill.fill_operation_id
                      ) <> 1
                ORDER BY fill.fill_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID fillId = rows.getObject("fill_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BAZAAR_FILL_EVIDENCE_MISMATCH",
                            fillId.toString(),
                            "Bazaar fill does not reconcile with order limits and its exact buyer commodity delivery"
                    ));
                }
            }
        }
    }

    private static void verifyOrderStateFromFills(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH fill_quantities AS (
                    SELECT order_id, SUM(quantity)::NUMERIC AS filled_quantity
                    FROM (
                        SELECT buy_order_id AS order_id, quantity FROM bazaar_fills
                        UNION ALL
                        SELECT sell_order_id AS order_id, quantity FROM bazaar_fills
                    ) fills
                    GROUP BY order_id
                ), reconstructed AS (
                    SELECT orders.*,
                           COALESCE(fills.filled_quantity, 0::NUMERIC) AS filled_quantity,
                           orders.original_quantity::NUMERIC
                             - COALESCE(fills.filled_quantity, 0::NUMERIC) AS unfilled_quantity
                    FROM bazaar_orders orders
                    LEFT JOIN fill_quantities fills ON fills.order_id = orders.order_id
                )
                SELECT order_id
                FROM reconstructed
                WHERE filled_quantity > original_quantity::NUMERIC
                   OR unfilled_quantity < 0
                   OR (
                        status = 'OPEN'
                        AND (
                             unfilled_quantity <= 0
                          OR remaining_quantity::NUMERIC IS DISTINCT FROM unfilled_quantity
                          OR cancel_operation_id IS NOT NULL
                          OR closed_at IS NOT NULL
                          OR (side = 'BUY' AND reserved_money_minor::NUMERIC
                                IS DISTINCT FROM unfilled_quantity * limit_price_minor::NUMERIC)
                          OR (side = 'SELL' AND reserved_money_minor <> 0)
                        )
                      )
                   OR (
                        status = 'FILLED'
                        AND (
                             unfilled_quantity IS DISTINCT FROM 0::NUMERIC
                          OR remaining_quantity <> 0
                          OR reserved_money_minor <> 0
                          OR cancel_operation_id IS NOT NULL
                          OR closed_at IS NULL
                        )
                      )
                   OR (
                        status = 'CANCELLED'
                        AND (
                             unfilled_quantity <= 0
                          OR remaining_quantity <> 0
                          OR reserved_money_minor <> 0
                          OR cancel_operation_id IS NULL
                          OR closed_at IS NULL
                        )
                      )
                   OR status NOT IN ('OPEN', 'FILLED', 'CANCELLED')
                ORDER BY order_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID orderId = rows.getObject("order_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BAZAAR_ORDER_STATE_MISMATCH",
                            orderId.toString(),
                            "Current Bazaar order remainder/reserve/status does not reconcile with immutable fill history"
                    ));
                }
            }
        }
    }

    private static void verifyCancellationEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH fill_quantities AS (
                    SELECT order_id, SUM(quantity)::NUMERIC AS filled_quantity
                    FROM (
                        SELECT buy_order_id AS order_id, quantity FROM bazaar_fills
                        UNION ALL
                        SELECT sell_order_id AS order_id, quantity FROM bazaar_fills
                    ) fills
                    GROUP BY order_id
                ), cancelled AS (
                    SELECT orders.*,
                           orders.original_quantity::NUMERIC
                             - COALESCE(fills.filled_quantity, 0::NUMERIC) AS cancelled_quantity
                    FROM bazaar_orders orders
                    LEFT JOIN fill_quantities fills ON fills.order_id = orders.order_id
                    WHERE orders.status = 'CANCELLED'
                )
                SELECT cancelled.order_id, cancelled.cancel_operation_id
                FROM cancelled
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = cancelled.cancel_operation_id
                WHERE cancelled.cancelled_quantity <= 0
                   OR operation.operation_id IS NULL
                   OR operation.operation_type IS DISTINCT FROM ?
                   OR operation.result ->> 'order_id' IS DISTINCT FROM cancelled.order_id::TEXT
                   OR operation.result ->> 'player_id' IS DISTINCT FROM cancelled.player_id::TEXT
                   OR operation.result ->> 'side' IS DISTINCT FROM cancelled.side
                   OR operation.result ->> 'reason' IS NULL
                   OR operation.result ->> 'reason' = ''
                   OR (
                        cancelled.side = 'BUY'
                        AND (
                             operation.result ->> 'returned_money_minor'
                                IS DISTINCT FROM (cancelled.cancelled_quantity
                                    * cancelled.limit_price_minor::NUMERIC)::TEXT
                          OR operation.result ->> 'returned_commodity_quantity' IS DISTINCT FROM '0'
                          OR operation.result ->> 'commodity_delivery_id' IS NOT NULL
                          OR (
                              SELECT COUNT(*)
                              FROM economic_ledger ledger
                              WHERE ledger.operation_id = cancelled.cancel_operation_id
                                AND ledger.player_id = cancelled.player_id
                                AND ledger.asset_type = 'CURRENCY'
                                AND ledger.asset_id = 'coin'
                                AND ledger.amount::NUMERIC
                                    = cancelled.cancelled_quantity * cancelled.limit_price_minor::NUMERIC
                                AND ledger.direction = 'CREDIT'
                                AND ledger.reason = operation.result ->> 'reason'
                          ) <> 1
                          OR (SELECT COUNT(*) FROM economic_ledger ledger
                              WHERE ledger.operation_id = cancelled.cancel_operation_id) <> 1
                        )
                      )
                   OR (
                        cancelled.side = 'SELL'
                        AND (
                             operation.result ->> 'returned_money_minor' IS DISTINCT FROM '0'
                          OR operation.result ->> 'returned_commodity_quantity'
                                IS DISTINCT FROM cancelled.cancelled_quantity::TEXT
                          OR operation.result ->> 'commodity_delivery_id' IS NULL
                          OR (SELECT COUNT(*) FROM economic_ledger ledger
                              WHERE ledger.operation_id = cancelled.cancel_operation_id) <> 0
                          OR (
                              SELECT COUNT(*)
                              FROM pending_commodity_deliveries delivery
                              WHERE delivery.delivery_id::TEXT = operation.result ->> 'commodity_delivery_id'
                                AND delivery.source_operation_id = cancelled.cancel_operation_id
                                AND delivery.player_id = cancelled.player_id
                                AND delivery.commodity_definition_id = cancelled.commodity_definition_id
                                AND delivery.quantity::NUMERIC = cancelled.cancelled_quantity
                          ) <> 1
                        )
                      )
                ORDER BY cancelled.order_id
                LIMIT ?
                """)) {
            statement.setString(1, CANCEL);
            statement.setInt(2, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID orderId = rows.getObject("order_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BAZAAR_CANCEL_EVIDENCE_MISMATCH",
                            orderId.toString(),
                            "Cancelled Bazaar order does not reconcile with its processed cancellation and exact escrow return"
                    ));
                }
            }
        }
    }

    private static void verifyMatchPassShape(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id
                FROM processed_operations
                WHERE operation_type = ?
                  AND (
                       jsonb_typeof(result) IS DISTINCT FROM 'object'
                    OR result ->> 'commodity_definition_id' IS NULL
                    OR result ->> 'commodity_definition_id' !~ '^[a-z0-9][a-z0-9._-]{0,63}$'
                    OR (result ->> 'fills' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                    OR (result ->> 'quantity_filled' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                    OR (result ->> 'gross_trade_value_minor' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                    OR (result ->> 'fees_destroyed_minor' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                    OR (result ->> 'max_fills' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                    OR result ->> 'reason' IS NULL
                    OR result ->> 'reason' = ''
                    OR CASE
                        WHEN (result ->> 'fills' ~ '^[0-9]+$')
                         AND (result ->> 'quantity_filled' ~ '^[0-9]+$')
                         AND (result ->> 'gross_trade_value_minor' ~ '^[0-9]+$')
                         AND (result ->> 'fees_destroyed_minor' ~ '^[0-9]+$')
                         AND (result ->> 'max_fills' ~ '^[1-9][0-9]*$')
                        THEN
                             (result ->> 'fills')::NUMERIC > (result ->> 'max_fills')::NUMERIC
                          OR ((result ->> 'fills')::NUMERIC = 0 AND (
                                 (result ->> 'quantity_filled')::NUMERIC <> 0
                              OR (result ->> 'gross_trade_value_minor')::NUMERIC <> 0
                              OR (result ->> 'fees_destroyed_minor')::NUMERIC <> 0
                             ))
                          OR ((result ->> 'fills')::NUMERIC > 0 AND (
                                 (result ->> 'quantity_filled')::NUMERIC <= 0
                              OR (result ->> 'gross_trade_value_minor')::NUMERIC <= 0
                             ))
                          OR (result ->> 'fees_destroyed_minor')::NUMERIC
                                > (result ->> 'gross_trade_value_minor')::NUMERIC
                        ELSE FALSE
                       END
                  )
                ORDER BY operation_id
                LIMIT ?
                """)) {
            statement.setString(1, MATCH);
            statement.setInt(2, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BAZAAR_MATCH_EVIDENCE_MISMATCH",
                            operationId.toString(),
                            "Bazaar match-pass processed result has an invalid frozen aggregate shape"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
