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

/** Read-only bounded reconciliation for irreversible unique-item salvage and its returned value. */
public final class SalvageIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public SalvageIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyOperationAndStateEvidence(connection, issues, maxIssues);
            verifyLedgerEvidence(connection, issues, maxIssues);
            verifyCommodityReturnEvidence(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyOperationAndStateEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH mismatches AS (
                    SELECT s.salvage_id
                    FROM salvage_records s
                    LEFT JOIN processed_operations op ON op.operation_id = s.operation_id
                    LEFT JOIN item_instances i ON i.item_instance_id = s.item_instance_id
                    LEFT JOIN item_provenance p
                      ON p.item_instance_id = s.item_instance_id
                     AND p.sequence_no = s.destroyed_item_version
                     AND p.operation_id = s.operation_id
                    LEFT JOIN player_sessions ps
                      ON ps.network_session_id::text = op.result ->> 'session_id'
                    LEFT JOIN player_state st ON st.player_id = s.player_id
                    LEFT JOIN wallets w ON w.player_id = s.player_id
                    WHERE op.operation_type IS DISTINCT FROM 'UNIQUE_ITEM_SALVAGE'
                       OR op.result ->> 'backend_id' IS NULL
                       OR op.result ->> 'reason' IS NULL
                       OR op.result ->> 'reason' !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                       OR op.result ->> 'payload_sha256' IS NULL
                       OR op.result ->> 'payload_sha256' !~ '^[0-9a-f]{64}$'
                       OR op.result ->> 'item_instance_id' IS DISTINCT FROM s.item_instance_id::text
                       OR CASE
                            WHEN jsonb_typeof(op.result -> 'expected_item_state_version') = 'number'
                            THEN (op.result ->> 'expected_item_state_version')::numeric + 1
                                 <> s.destroyed_item_version::numeric
                            ELSE TRUE
                          END
                       OR op.result #>> '{result,salvage_id}' IS DISTINCT FROM s.salvage_id::text
                       OR op.result #>> '{result,operation_id}' IS DISTINCT FROM s.operation_id::text
                       OR op.result #>> '{result,player_id}' IS DISTINCT FROM s.player_id::text
                       OR op.result #>> '{result,item_instance_id}' IS DISTINCT FROM s.item_instance_id::text
                       OR op.result #>> '{result,item_definition_id}' IS DISTINCT FROM s.item_definition_id
                       OR op.result #>> '{result,destroyed_item_version}' IS DISTINCT FROM s.destroyed_item_version::text
                       OR op.result #>> '{result,coin_return_minor}' IS DISTINCT FROM s.coin_return_minor::text
                       OR CASE
                            WHEN jsonb_typeof(op.result -> 'expected_player_state_version') = 'number'
                             AND jsonb_typeof(op.result #> '{result,player_state_version}') = 'number'
                            THEN (op.result ->> 'expected_player_state_version')::numeric + 1
                                 <> (op.result #>> '{result,player_state_version}')::numeric
                            ELSE TRUE
                          END
                       OR ps.network_session_id IS NULL
                       OR ps.player_id IS DISTINCT FROM s.player_id
                       OR CASE
                            WHEN jsonb_typeof(op.result #> '{result,player_state_version}') = 'number'
                            THEN ps.state_version::numeric < (op.result #>> '{result,player_state_version}')::numeric
                              OR st.state_version::numeric < (op.result #>> '{result,player_state_version}')::numeric
                            ELSE TRUE
                          END
                       OR CASE
                            WHEN jsonb_typeof(op.result #> '{result,wallet_state_version}') = 'number'
                            THEN w.state_version::numeric < (op.result #>> '{result,wallet_state_version}')::numeric
                            ELSE TRUE
                          END
                       OR CASE
                            WHEN jsonb_typeof(op.result #> '{result,wallet_balance_minor}') = 'number'
                            THEN (op.result #>> '{result,wallet_balance_minor}')::numeric < 0
                            ELSE TRUE
                          END
                       OR i.item_instance_id IS NULL
                       OR i.definition_id IS DISTINCT FROM s.item_definition_id
                       OR i.location_kind IS DISTINCT FROM 'DESTROYED'
                       OR i.location_id IS NOT NULL
                       OR i.state_version IS DISTINCT FROM s.destroyed_item_version
                       OR p.item_instance_id IS NULL
                       OR p.event_type IS DISTINCT FROM 'DESTROYED'
                       OR p.from_location_kind IS DISTINCT FROM 'PLAYER_INVENTORY'
                       OR p.from_location_id IS DISTINCT FROM s.player_id
                       OR p.to_location_kind IS DISTINCT FROM 'DESTROYED'
                       OR p.to_location_id IS NOT NULL
                       OR p.reason IS DISTINCT FROM op.result ->> 'reason'
                       OR p.actor_player_id IS DISTINCT FROM s.player_id
                    UNION
                    SELECT NULL::uuid
                    FROM processed_operations op
                    LEFT JOIN salvage_records s ON s.operation_id = op.operation_id
                    WHERE op.operation_type = 'UNIQUE_ITEM_SALVAGE'
                      AND s.salvage_id IS NULL
                )
                SELECT salvage_id
                FROM mismatches
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID salvageId = rows.getObject("salvage_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "SALVAGE_OPERATION_EVIDENCE_MISMATCH",
                            salvageId == null ? "orphan_processed_salvage" : salvageId.toString(),
                            "Salvage record does not reconcile to its processed request, state versions, or destruction provenance"
                    ));
                }
            }
        }
    }

    private static void verifyLedgerEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH returns AS (
                    SELECT s.salvage_id,
                           s.operation_id,
                           s.player_id,
                           s.coin_return_minor,
                           r.key AS commodity_definition_id,
                           r.value::bigint AS quantity,
                           ROW_NUMBER() OVER (PARTITION BY s.salvage_id ORDER BY r.key) - 1 AS commodity_ordinal
                    FROM salvage_records s
                    LEFT JOIN LATERAL jsonb_each_text(s.commodity_returns) r ON TRUE
                ), expected_counts AS (
                    SELECT s.salvage_id,
                           s.operation_id,
                           1
                           + CASE WHEN s.coin_return_minor > 0 THEN 1 ELSE 0 END
                           + (SELECT COUNT(*) FROM jsonb_object_keys(s.commodity_returns)) AS expected_count
                    FROM salvage_records s
                ), bad AS (
                    SELECT s.salvage_id
                    FROM salvage_records s
                    LEFT JOIN processed_operations op ON op.operation_id = s.operation_id
                    WHERE (SELECT COUNT(*) FROM economic_ledger l WHERE l.operation_id = s.operation_id)
                          IS DISTINCT FROM (
                              SELECT expected_count FROM expected_counts e WHERE e.salvage_id = s.salvage_id
                          )
                       OR NOT EXISTS (
                            SELECT 1
                            FROM economic_ledger l
                            WHERE l.operation_id = s.operation_id
                              AND l.line_no = 0
                              AND l.player_id = s.player_id
                              AND l.asset_type = 'ITEM_INSTANCE'
                              AND l.asset_id = s.item_instance_id::text
                              AND l.amount = 1
                              AND l.direction = 'DEBIT'
                              AND l.reason = op.result ->> 'reason'
                       )
                       OR (s.coin_return_minor > 0 AND NOT EXISTS (
                            SELECT 1
                            FROM economic_ledger l
                            WHERE l.operation_id = s.operation_id
                              AND l.line_no = 1
                              AND l.player_id = s.player_id
                              AND l.asset_type = 'CURRENCY'
                              AND l.asset_id = 'coin'
                              AND l.amount = s.coin_return_minor
                              AND l.direction = 'CREDIT'
                              AND l.reason = op.result ->> 'reason'
                       ))
                       OR (s.coin_return_minor = 0 AND EXISTS (
                            SELECT 1
                            FROM economic_ledger l
                            WHERE l.operation_id = s.operation_id
                              AND l.asset_type = 'CURRENCY'
                              AND l.asset_id = 'coin'
                       ))
                       OR EXISTS (
                            SELECT 1
                            FROM returns r
                            WHERE r.commodity_definition_id IS NOT NULL
                              AND NOT EXISTS (
                                SELECT 1
                                FROM economic_ledger l
                                WHERE l.operation_id = r.operation_id
                                  AND l.line_no = 1
                                      + CASE WHEN r.coin_return_minor > 0 THEN 1 ELSE 0 END
                                      + r.commodity_ordinal
                                  AND l.player_id = r.player_id
                                  AND l.asset_type = 'COMMODITY'
                                  AND l.asset_id = r.commodity_definition_id
                                  AND l.amount = r.quantity
                                  AND l.direction = 'CREDIT'
                                  AND l.reason = op.result ->> 'reason'
                              )
                       )
                )
                SELECT salvage_id
                FROM bad
                ORDER BY salvage_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID salvageId = rows.getObject("salvage_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "SALVAGE_LEDGER_EVIDENCE_MISMATCH",
                            salvageId.toString(),
                            "Salvage item debit and returned Coin/commodity ledger lines do not match the immutable salvage record"
                    ));
                }
            }
        }
    }

    private static void verifyCommodityReturnEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH base AS (
                    SELECT s.salvage_id, s.operation_id, s.player_id, s.commodity_returns, op.result
                    FROM salvage_records s
                    LEFT JOIN processed_operations op ON op.operation_id = s.operation_id
                ), processed_returns AS (
                    SELECT b.salvage_id,
                           b.player_id,
                           e.value ->> 'delivery_id' AS delivery_id,
                           e.value ->> 'commodity_definition_id' AS commodity_definition_id,
                           e.value ->> 'quantity' AS quantity
                    FROM base b
                    LEFT JOIN LATERAL jsonb_array_elements(
                        CASE
                            WHEN jsonb_typeof(b.result #> '{result,commodity_returns}') = 'array'
                            THEN b.result #> '{result,commodity_returns}'
                            ELSE '[]'::jsonb
                        END
                    ) e ON TRUE
                ), bad AS (
                    SELECT b.salvage_id
                    FROM base b
                    WHERE jsonb_typeof(b.result #> '{result,commodity_returns}') IS DISTINCT FROM 'array'
                       OR jsonb_array_length(
                            CASE
                                WHEN jsonb_typeof(b.result #> '{result,commodity_returns}') = 'array'
                                THEN b.result #> '{result,commodity_returns}'
                                ELSE '[]'::jsonb
                            END
                          ) IS DISTINCT FROM (SELECT COUNT(*) FROM jsonb_object_keys(b.commodity_returns))
                       OR EXISTS (
                            SELECT 1
                            FROM jsonb_each_text(b.commodity_returns) expected
                            WHERE (
                                SELECT COUNT(*)
                                FROM processed_returns pr
                                WHERE pr.salvage_id = b.salvage_id
                                  AND pr.commodity_definition_id = expected.key
                                  AND pr.quantity = expected.value
                            ) <> 1
                       )
                       OR EXISTS (
                            SELECT 1
                            FROM processed_returns pr
                            LEFT JOIN pending_commodity_deliveries d
                              ON d.delivery_id::text = pr.delivery_id
                            WHERE pr.salvage_id = b.salvage_id
                              AND pr.commodity_definition_id IS NOT NULL
                              AND (
                                d.delivery_id IS NULL
                                OR d.player_id IS DISTINCT FROM b.player_id
                                OR d.commodity_definition_id IS DISTINCT FROM pr.commodity_definition_id
                                OR d.quantity::text IS DISTINCT FROM pr.quantity
                              )
                       )
                )
                SELECT salvage_id
                FROM bad
                ORDER BY salvage_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID salvageId = rows.getObject("salvage_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "SALVAGE_RETURN_DELIVERY_MISMATCH",
                            salvageId.toString(),
                            "Salvage commodity returns do not reconcile to the processed result and exact durable deliveries"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
