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

/** Read-only bounded verification for persistent Map and Bounty value/evidence chains. */
public final class PersistentPveIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public PersistentPveIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyMapOpenConsumptionEvidence(connection, issues, maxIssues);
            verifyMapClearEvidence(connection, issues, maxIssues);
            verifyBountyCompletionEvidence(connection, issues, maxIssues);
            verifyBountyPouchConservation(connection, issues, maxIssues);
            verifyBountyWithdrawalDeliveryEvidence(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyMapOpenConsumptionEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.run_id
                FROM map_runs r
                LEFT JOIN item_instances i ON i.item_instance_id = r.source_map_item_id
                LEFT JOIN item_provenance p
                  ON p.item_instance_id = r.source_map_item_id
                 AND p.sequence_no = r.source_item_expected_state_version + 1
                 AND p.operation_id = r.open_operation_id
                 AND p.event_type = 'DESTROYED'
                 AND p.from_location_kind = 'PLAYER_INVENTORY'
                 AND p.from_location_id = r.opened_by_player_id
                 AND p.to_location_kind = 'DESTROYED'
                 AND p.to_location_id IS NULL
                WHERE r.open_operation_id IS NOT NULL
                  AND (
                      i.item_instance_id IS NULL
                      OR i.location_kind IS DISTINCT FROM 'DESTROYED'
                      OR i.location_id IS NOT NULL
                      OR i.state_version IS DISTINCT FROM r.source_item_expected_state_version + 1
                      OR p.item_instance_id IS NULL
                  )
                ORDER BY r.created_at, r.run_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID runId = rows.getObject("run_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "MAP_OPEN_CONSUMPTION_EVIDENCE_MISMATCH",
                            runId.toString(),
                            "Map run does not reconcile to the exact consumed source item/provenance evidence"
                    ));
                }
            }
        }
    }

    private static void verifyMapClearEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(r.run_id, c.run_id) AS run_id
                FROM map_runs r
                FULL OUTER JOIN map_clears c ON c.run_id = r.run_id
                WHERE (
                    r.status = 'COMPLETED'
                    AND (
                        c.run_id IS NULL
                        OR c.difficulty IS DISTINCT FROM r.difficulty
                        OR c.world_era_id IS DISTINCT FROM r.world_era_id
                        OR c.balance_version IS DISTINCT FROM r.balance_version
                        OR c.completed_at IS DISTINCT FROM r.finished_at
                        OR c.solo IS DISTINCT FROM (
                            (SELECT COUNT(*) FROM map_run_participants p WHERE p.run_id = r.run_id) = 1
                        )
                    )
                ) OR (
                    c.run_id IS NOT NULL
                    AND (
                        r.run_id IS NULL
                        OR r.status IS DISTINCT FROM 'COMPLETED'
                        OR c.difficulty IS DISTINCT FROM r.difficulty
                        OR c.world_era_id IS DISTINCT FROM r.world_era_id
                        OR c.balance_version IS DISTINCT FROM r.balance_version
                        OR c.completed_at IS DISTINCT FROM r.finished_at
                    )
                )
                ORDER BY run_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID runId = rows.getObject("run_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "MAP_CLEAR_EVIDENCE_MISMATCH",
                            runId.toString(),
                            "Map clear evidence does not match the authoritative terminal run"
                    ));
                }
            }
        }
    }

    private static void verifyBountyCompletionEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(c.contract_id::text, op.result #>> '{contract,contract_id}') AS subject_id
                FROM bounty_contracts c
                FULL OUTER JOIN processed_operations op ON op.operation_id = c.reward_operation_id
                WHERE (
                    c.status = 'COMPLETED'
                    AND (
                        c.reward_operation_id IS NULL
                        OR op.operation_id IS NULL
                        OR op.operation_type IS DISTINCT FROM 'BOUNTY_BOSS_COMPLETE'
                        OR op.result #>> '{contract,contract_id}' IS DISTINCT FROM c.contract_id::text
                        OR op.result #>> '{contract,player_id}' IS DISTINCT FROM c.player_id::text
                        OR op.result #>> '{contract,family_id}' IS DISTINCT FROM c.family_id
                        OR op.result #>> '{contract,status}' IS DISTINCT FROM 'COMPLETED'
                        OR jsonb_typeof(op.result -> 'pouch_rewards') IS DISTINCT FROM 'object'
                    )
                ) OR (
                    op.operation_type = 'BOUNTY_BOSS_COMPLETE'
                    AND (
                        c.contract_id IS NULL
                        OR c.status IS DISTINCT FROM 'COMPLETED'
                        OR c.reward_operation_id IS DISTINCT FROM op.operation_id
                        OR op.result #>> '{contract,contract_id}' IS DISTINCT FROM c.contract_id::text
                    )
                )
                ORDER BY subject_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String subject = rows.getString("subject_id");
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BOUNTY_COMPLETION_EVIDENCE_MISMATCH",
                            subject == null ? "unknown" : subject,
                            "Completed Bounty contract does not reconcile to its exactly-once boss reward evidence"
                    ));
                }
            }
        }
    }

    private static void verifyBountyPouchConservation(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH rewards AS (
                    SELECT c.player_id,
                           c.family_id,
                           reward.key AS commodity_definition_id,
                           SUM(reward.value::bigint) AS quantity
                    FROM bounty_contracts c
                    JOIN processed_operations op ON op.operation_id = c.reward_operation_id
                    CROSS JOIN LATERAL jsonb_each_text(op.result -> 'pouch_rewards') reward
                    WHERE c.status = 'COMPLETED'
                      AND op.operation_type = 'BOUNTY_BOSS_COMPLETE'
                      AND op.result #>> '{contract,contract_id}' = c.contract_id::text
                    GROUP BY c.player_id, c.family_id, reward.key
                ),
                withdrawals AS (
                    SELECT (op.result ->> 'player_id')::uuid AS player_id,
                           op.result ->> 'family_id' AS family_id,
                           op.result ->> 'commodity_definition_id' AS commodity_definition_id,
                           SUM((op.result ->> 'quantity')::bigint) AS quantity
                    FROM processed_operations op
                    WHERE op.operation_type = 'BOUNTY_POUCH_WITHDRAW'
                    GROUP BY 1, 2, 3
                ),
                expected AS (
                    SELECT COALESCE(r.player_id, w.player_id) AS player_id,
                           COALESCE(r.family_id, w.family_id) AS family_id,
                           COALESCE(r.commodity_definition_id, w.commodity_definition_id) AS commodity_definition_id,
                           COALESCE(r.quantity, 0) - COALESCE(w.quantity, 0) AS quantity
                    FROM rewards r
                    FULL OUTER JOIN withdrawals w
                      ON w.player_id = r.player_id
                     AND w.family_id = r.family_id
                     AND w.commodity_definition_id = r.commodity_definition_id
                )
                SELECT COALESCE(b.player_id, e.player_id) AS player_id,
                       COALESCE(b.family_id, e.family_id) AS family_id,
                       COALESCE(b.commodity_definition_id, e.commodity_definition_id) AS commodity_definition_id,
                       COALESCE(b.quantity, 0) AS actual_quantity,
                       COALESCE(e.quantity, 0) AS expected_quantity
                FROM bounty_pouch_balances b
                FULL OUTER JOIN expected e
                  ON e.player_id = b.player_id
                 AND e.family_id = b.family_id
                 AND e.commodity_definition_id = b.commodity_definition_id
                WHERE COALESCE(b.quantity, 0) IS DISTINCT FROM COALESCE(e.quantity, 0)
                ORDER BY 1, 2, 3
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    String family = rows.getString("family_id");
                    String commodity = rows.getString("commodity_definition_id");
                    long actual = rows.getLong("actual_quantity");
                    long expected = rows.getLong("expected_quantity");
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BOUNTY_POUCH_CONSERVATION_MISMATCH",
                            playerId + ":" + family + ":" + commodity,
                            "Bounty pouch quantity " + actual + " does not match completed rewards minus withdrawals " + expected
                    ));
                }
            }
        }
    }

    private static void verifyBountyWithdrawalDeliveryEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT op.operation_id
                FROM processed_operations op
                LEFT JOIN pending_commodity_deliveries d
                  ON d.delivery_id = (op.result #>> '{result,delivery_id}')::uuid
                WHERE op.operation_type = 'BOUNTY_POUCH_WITHDRAW'
                  AND (
                      d.delivery_id IS NULL
                      OR d.source_operation_id IS DISTINCT FROM op.operation_id
                      OR d.player_id::text IS DISTINCT FROM op.result ->> 'player_id'
                      OR d.commodity_definition_id IS DISTINCT FROM op.result ->> 'commodity_definition_id'
                      OR d.quantity IS DISTINCT FROM (op.result ->> 'quantity')::bigint
                  )
                ORDER BY op.operation_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BOUNTY_WITHDRAWAL_DELIVERY_MISMATCH",
                            operationId.toString(),
                            "Bounty pouch withdrawal does not reconcile to its durable commodity delivery"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
