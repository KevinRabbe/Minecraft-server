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

/** Read-only bounded reconstruction of terminal secure-trade settlement/cancellation evidence. */
public final class SecureTradeIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String SETTLE_OPERATION = "SECURE_TRADE_SETTLE";
    private static final String CANCEL_OPERATION = "SECURE_TRADE_CANCEL";

    private final DataSource dataSource;

    public SecureTradeIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyTerminalOperation(connection, issues, maxIssues);
            verifyTerminalDeliveries(connection, issues, maxIssues);
            verifyTerminalLedger(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyTerminalOperation(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT trade.trade_id
                FROM secure_trades trade
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = CASE
                       WHEN trade.status = 'SETTLED' THEN trade.settle_operation_id
                       WHEN trade.status = 'CANCELLED' THEN trade.cancel_operation_id
                       ELSE NULL
                     END
                WHERE trade.status IN ('SETTLED', 'CANCELLED')
                  AND (
                       operation.operation_id IS NULL
                    OR operation.operation_type IS DISTINCT FROM CASE
                         WHEN trade.status = 'SETTLED' THEN ?
                         ELSE ?
                       END
                    OR operation.result ->> 'trade_id' IS DISTINCT FROM trade.trade_id::TEXT
                    OR operation.result ->> 'reason' IS NULL
                    OR operation.result ->> 'reason' = ''
                    OR jsonb_typeof(operation.result -> 'trade') IS DISTINCT FROM 'object'
                    OR operation.result -> 'trade' ->> 'trade_id' IS DISTINCT FROM trade.trade_id::TEXT
                    OR operation.result -> 'trade' ->> 'player_a_id' IS DISTINCT FROM trade.player_a_id::TEXT
                    OR operation.result -> 'trade' ->> 'player_b_id' IS DISTINCT FROM trade.player_b_id::TEXT
                    OR operation.result -> 'trade' ->> 'status' IS DISTINCT FROM trade.status
                    OR operation.result -> 'trade' ->> 'revision' IS DISTINCT FROM trade.revision::TEXT
                    OR operation.result -> 'trade' ->> 'player_a_confirmed_revision'
                         IS DISTINCT FROM trade.player_a_confirmed_revision::TEXT
                    OR operation.result -> 'trade' ->> 'player_b_confirmed_revision'
                         IS DISTINCT FROM trade.player_b_confirmed_revision::TEXT
                    OR jsonb_typeof(operation.result -> 'wallet_balances_minor') IS DISTINCT FROM 'object'
                    OR jsonb_typeof((operation.result -> 'wallet_balances_minor') -> trade.player_a_id::TEXT)
                         IS DISTINCT FROM 'number'
                    OR jsonb_typeof((operation.result -> 'wallet_balances_minor') -> trade.player_b_id::TEXT)
                         IS DISTINCT FROM 'number'
                    OR jsonb_typeof(operation.result -> 'deliveries') IS DISTINCT FROM 'array'
                    OR jsonb_array_length(CASE
                         WHEN jsonb_typeof(operation.result -> 'deliveries') = 'array'
                         THEN operation.result -> 'deliveries'
                         ELSE '[]'::JSONB
                       END) <> (
                         SELECT COUNT(*)
                         FROM secure_trade_deliveries delivery
                         WHERE delivery.trade_id = trade.trade_id
                       )
                    OR EXISTS (
                         SELECT 1
                         FROM secure_trade_deliveries delivery
                         WHERE delivery.trade_id = trade.trade_id
                           AND NOT EXISTS (
                               SELECT 1
                               FROM jsonb_array_elements(CASE
                                   WHEN jsonb_typeof(operation.result -> 'deliveries') = 'array'
                                   THEN operation.result -> 'deliveries'
                                   ELSE '[]'::JSONB
                               END) result_delivery
                               WHERE result_delivery ->> 'trade_id' = trade.trade_id::TEXT
                                 AND result_delivery ->> 'delivery_id' = delivery.delivery_id::TEXT
                                 AND result_delivery ->> 'kind' = delivery.delivery_kind
                                 AND result_delivery ->> 'source_owner_player_id' = delivery.source_owner_player_id::TEXT
                                 AND result_delivery ->> 'recipient_player_id' = delivery.recipient_player_id::TEXT
                                 AND result_delivery ->> 'item_instance_id' IS NOT DISTINCT FROM delivery.item_instance_id::TEXT
                                 AND result_delivery ->> 'commodity_definition_id'
                                      IS NOT DISTINCT FROM delivery.commodity_definition_id
                                 AND result_delivery ->> 'quantity' IS NOT DISTINCT FROM delivery.quantity::TEXT
                           )
                       )
                  )
                ORDER BY trade.trade_id
                LIMIT ?
                """)) {
            statement.setString(1, SETTLE_OPERATION);
            statement.setString(2, CANCEL_OPERATION);
            statement.setInt(3, remaining);
            addIssues(
                    statement,
                    issues,
                    "SECURE_TRADE_TERMINAL_OPERATION_MISMATCH",
                    "Terminal secure trade does not reconcile with its exact processed resolution result"
            );
        }
    }

    private static void verifyTerminalDeliveries(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT trade.trade_id
                FROM secure_trades trade
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = CASE
                       WHEN trade.status = 'SETTLED' THEN trade.settle_operation_id
                       WHEN trade.status = 'CANCELLED' THEN trade.cancel_operation_id
                       ELSE NULL
                     END
                WHERE trade.status IN ('SETTLED', 'CANCELLED')
                  AND (
                       operation.operation_id IS NULL
                    OR (
                         SELECT COUNT(*)
                         FROM secure_trade_deliveries delivery
                         WHERE delivery.trade_id = trade.trade_id
                       ) <> (
                         SELECT COUNT(*)
                         FROM secure_trade_commodity_escrow commodity
                         WHERE commodity.trade_id = trade.trade_id
                       ) + (
                         SELECT COUNT(*)
                         FROM secure_trade_unique_items item
                         WHERE item.trade_id = trade.trade_id
                       )
                    OR EXISTS (
                         SELECT 1
                         FROM secure_trade_commodity_escrow commodity
                         WHERE commodity.trade_id = trade.trade_id
                           AND NOT EXISTS (
                               SELECT 1
                               FROM secure_trade_deliveries delivery
                               JOIN pending_commodity_deliveries pending
                                 ON pending.delivery_id = delivery.delivery_id
                               WHERE delivery.trade_id = trade.trade_id
                                 AND delivery.delivery_kind = 'COMMODITY'
                                 AND delivery.source_owner_player_id = commodity.owner_player_id
                                 AND delivery.recipient_player_id = CASE
                                      WHEN trade.status = 'SETTLED' AND commodity.owner_player_id = trade.player_a_id
                                      THEN trade.player_b_id
                                      WHEN trade.status = 'SETTLED'
                                      THEN trade.player_a_id
                                      ELSE commodity.owner_player_id
                                    END
                                 AND delivery.commodity_definition_id = commodity.commodity_definition_id
                                 AND delivery.quantity = commodity.quantity
                                 AND pending.player_id = delivery.recipient_player_id
                                 AND pending.commodity_definition_id = delivery.commodity_definition_id
                                 AND pending.quantity = delivery.quantity
                           )
                       )
                    OR EXISTS (
                         SELECT 1
                         FROM secure_trade_unique_items escrow
                         WHERE escrow.trade_id = trade.trade_id
                           AND NOT EXISTS (
                               SELECT 1
                               FROM secure_trade_deliveries delivery
                               JOIN pending_unique_deliveries pending
                                 ON pending.delivery_id = delivery.delivery_id
                               JOIN item_provenance provenance
                                 ON provenance.item_instance_id = delivery.item_instance_id
                                AND provenance.sequence_no = escrow.escrow_item_version + 1
                                AND provenance.operation_id = pending.issue_operation_id
                               WHERE delivery.trade_id = trade.trade_id
                                 AND delivery.delivery_kind = 'UNIQUE_ITEM'
                                 AND delivery.source_owner_player_id = escrow.owner_player_id
                                 AND delivery.recipient_player_id = CASE
                                      WHEN trade.status = 'SETTLED' AND escrow.owner_player_id = trade.player_a_id
                                      THEN trade.player_b_id
                                      WHEN trade.status = 'SETTLED'
                                      THEN trade.player_a_id
                                      ELSE escrow.owner_player_id
                                    END
                                 AND delivery.item_instance_id = escrow.item_instance_id
                                 AND pending.recipient_player_id = delivery.recipient_player_id
                                 AND pending.item_instance_id = delivery.item_instance_id
                                 AND pending.issue_reason = operation.result ->> 'reason'
                                 AND provenance.event_type = 'MOVED'
                                 AND provenance.from_location_kind = 'TRADE_ESCROW'
                                 AND provenance.from_location_id = trade.trade_id
                                 AND provenance.to_location_kind = 'PENDING_DELIVERY'
                                 AND provenance.to_location_id = delivery.delivery_id
                                 AND provenance.reason = operation.result ->> 'reason'
                                 AND provenance.actor_player_id IS NULL
                           )
                       )
                  )
                ORDER BY trade.trade_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            addIssues(
                    statement,
                    issues,
                    "SECURE_TRADE_DELIVERY_EVIDENCE_MISMATCH",
                    "Terminal secure trade deliveries do not reconstruct exactly from frozen escrow and durable delivery/provenance evidence"
            );
        }
    }

    private static void verifyTerminalLedger(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT trade.trade_id
                FROM secure_trades trade
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = CASE
                       WHEN trade.status = 'SETTLED' THEN trade.settle_operation_id
                       WHEN trade.status = 'CANCELLED' THEN trade.cancel_operation_id
                       ELSE NULL
                     END
                WHERE trade.status IN ('SETTLED', 'CANCELLED')
                  AND (
                       operation.operation_id IS NULL
                    OR (
                         SELECT COUNT(*)
                         FROM economic_ledger ledger
                         WHERE ledger.operation_id = operation.operation_id
                       ) <> (
                         SELECT COUNT(*) FROM secure_trade_coin_escrow coin WHERE coin.trade_id = trade.trade_id
                       ) + (
                         SELECT COUNT(*) FROM secure_trade_commodity_escrow commodity WHERE commodity.trade_id = trade.trade_id
                       ) + (
                         SELECT COUNT(*) FROM secure_trade_unique_items item WHERE item.trade_id = trade.trade_id
                       )
                    OR EXISTS (
                         SELECT 1
                         FROM secure_trade_coin_escrow coin
                         WHERE coin.trade_id = trade.trade_id
                           AND NOT EXISTS (
                               SELECT 1
                               FROM economic_ledger ledger
                               WHERE ledger.operation_id = operation.operation_id
                                 AND ledger.player_id = CASE
                                      WHEN trade.status = 'SETTLED' AND coin.owner_player_id = trade.player_a_id
                                      THEN trade.player_b_id
                                      WHEN trade.status = 'SETTLED'
                                      THEN trade.player_a_id
                                      ELSE coin.owner_player_id
                                    END
                                 AND ledger.asset_type = 'CURRENCY'
                                 AND ledger.asset_id = 'coin'
                                 AND ledger.amount = coin.amount_minor
                                 AND ledger.direction = 'CREDIT'
                                 AND ledger.reason = operation.result ->> 'reason'
                           )
                       )
                    OR EXISTS (
                         SELECT 1
                         FROM secure_trade_commodity_escrow commodity
                         WHERE commodity.trade_id = trade.trade_id
                           AND NOT EXISTS (
                               SELECT 1
                               FROM economic_ledger ledger
                               WHERE ledger.operation_id = operation.operation_id
                                 AND ledger.player_id = CASE
                                      WHEN trade.status = 'SETTLED' AND commodity.owner_player_id = trade.player_a_id
                                      THEN trade.player_b_id
                                      WHEN trade.status = 'SETTLED'
                                      THEN trade.player_a_id
                                      ELSE commodity.owner_player_id
                                    END
                                 AND ledger.asset_type = 'COMMODITY'
                                 AND ledger.asset_id = commodity.commodity_definition_id
                                 AND ledger.amount = commodity.quantity
                                 AND ledger.direction = 'CREDIT'
                                 AND ledger.reason = operation.result ->> 'reason'
                           )
                       )
                    OR EXISTS (
                         SELECT 1
                         FROM secure_trade_unique_items item
                         WHERE item.trade_id = trade.trade_id
                           AND NOT EXISTS (
                               SELECT 1
                               FROM economic_ledger ledger
                               WHERE ledger.operation_id = operation.operation_id
                                 AND ledger.player_id = CASE
                                      WHEN trade.status = 'SETTLED' AND item.owner_player_id = trade.player_a_id
                                      THEN trade.player_b_id
                                      WHEN trade.status = 'SETTLED'
                                      THEN trade.player_a_id
                                      ELSE item.owner_player_id
                                    END
                                 AND ledger.asset_type = 'ITEM_INSTANCE'
                                 AND ledger.asset_id = item.item_instance_id::TEXT
                                 AND ledger.amount = 1
                                 AND ledger.direction = 'CREDIT'
                                 AND ledger.reason = operation.result ->> 'reason'
                           )
                       )
                  )
                ORDER BY trade.trade_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            addIssues(
                    statement,
                    issues,
                    "SECURE_TRADE_LEDGER_EVIDENCE_MISMATCH",
                    "Terminal secure trade Coin/commodity/item credits do not reconcile exactly with frozen escrow"
            );
        }
    }

    private static void addIssues(
            PreparedStatement statement,
            List<IntegrityIssue> issues,
            String code,
            String message
    ) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID tradeId = rows.getObject("trade_id", UUID.class);
                issues.add(new IntegrityIssue(IntegritySeverity.CRITICAL, code, tradeId.toString(), message));
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
