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

/** Read-only bounded reconciliation for completed Map reward settlement, grant, and delivery evidence. */
public final class MapRewardIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public MapRewardIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifySettlementLink(connection, issues, maxIssues);
            verifySettlementHasGrant(connection, issues, maxIssues);
            verifyGrantSourceEvidence(connection, issues, maxIssues);
            verifyFulfilledGrantDelivery(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifySettlementLink(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(r.run_id, s.run_id) AS run_id
                FROM map_runs r
                FULL OUTER JOIN map_reward_settlements s ON s.run_id = r.run_id
                WHERE (
                    r.reward_operation_id IS NOT NULL
                    AND (
                        s.run_id IS NULL
                        OR s.settlement_operation_id IS DISTINCT FROM r.reward_operation_id
                    )
                ) OR (
                    s.run_id IS NOT NULL
                    AND (
                        r.run_id IS NULL
                        OR r.status IS DISTINCT FROM 'COMPLETED'
                        OR r.reward_operation_id IS DISTINCT FROM s.settlement_operation_id
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
                            "MAP_REWARD_SETTLEMENT_EVIDENCE_MISMATCH",
                            runId == null ? "unknown" : runId.toString(),
                            "Map reward settlement does not match the completed run reward operation"
                    ));
                }
            }
        }
    }

    private static void verifySettlementHasGrant(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.run_id
                FROM map_reward_settlements s
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM map_reward_grants g
                    WHERE g.run_id = s.run_id
                )
                ORDER BY s.settled_at, s.run_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID runId = rows.getObject("run_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "MAP_REWARD_SETTLEMENT_EVIDENCE_MISMATCH",
                            runId.toString(),
                            "Map reward settlement has no durable reward grants"
                    ));
                }
            }
        }
    }

    private static void verifyGrantSourceEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH ordered_grants AS (
                    SELECT g.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY g.run_id
                               ORDER BY g.ordinal, g.grant_id
                           ) - 1 AS expected_ordinal
                    FROM map_reward_grants g
                )
                SELECT g.grant_id
                FROM ordered_grants g
                LEFT JOIN map_reward_settlements s ON s.run_id = g.run_id
                LEFT JOIN map_run_participants p
                  ON p.run_id = g.run_id
                 AND p.player_id = g.player_id
                WHERE s.run_id IS NULL
                   OR p.player_id IS NULL
                   OR g.ordinal IS DISTINCT FROM g.expected_ordinal
                   OR g.created_at IS DISTINCT FROM s.settled_at
                   OR (g.reward_kind = 'MAP' AND g.map_profile IS NULL)
                   OR (g.reward_kind <> 'MAP' AND g.map_profile IS NOT NULL)
                ORDER BY g.run_id, g.ordinal, g.grant_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID grantId = rows.getObject("grant_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "MAP_REWARD_GRANT_EVIDENCE_MISMATCH",
                            grantId.toString(),
                            "Map reward grant does not reconcile to its settlement, participant, ordinal, or reward shape"
                    ));
                }
            }
        }
    }

    private static void verifyFulfilledGrantDelivery(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT g.grant_id
                FROM map_reward_grants g
                LEFT JOIN pending_commodity_deliveries c
                  ON c.delivery_id = g.fulfillment_reference_id
                LEFT JOIN pending_unique_deliveries u
                  ON u.delivery_id = g.fulfillment_reference_id
                LEFT JOIN item_instances i
                  ON i.item_instance_id = u.item_instance_id
                LEFT JOIN map_item_profiles m
                  ON m.item_instance_id = u.item_instance_id
                WHERE g.status = 'FULFILLED'
                  AND (
                    g.fulfillment_operation_id IS NULL
                    OR g.fulfillment_reference_id IS NULL
                    OR g.fulfilled_at IS NULL
                    OR (
                        g.reward_kind = 'COMMODITY'
                        AND (
                            c.delivery_id IS NULL
                            OR c.source_operation_id IS DISTINCT FROM g.fulfillment_operation_id
                            OR c.player_id IS DISTINCT FROM g.player_id
                            OR c.commodity_definition_id IS DISTINCT FROM g.definition_id
                            OR c.quantity IS DISTINCT FROM g.quantity
                        )
                    )
                    OR (
                        g.reward_kind = 'UNIQUE_ITEM'
                        AND (
                            u.delivery_id IS NULL
                            OR u.issue_operation_id IS DISTINCT FROM g.fulfillment_operation_id
                            OR u.recipient_player_id IS DISTINCT FROM g.player_id
                            OR u.issue_reason IS DISTINCT FROM 'map.reward'
                            OR i.item_instance_id IS NULL
                            OR i.definition_id IS DISTINCT FROM g.definition_id
                            OR i.original_owner_player_id IS DISTINCT FROM g.player_id
                            OR i.created_by_operation_id IS DISTINCT FROM g.fulfillment_operation_id
                            OR i.created_reason IS DISTINCT FROM 'map.reward'
                            OR m.item_instance_id IS NOT NULL
                        )
                    )
                    OR (
                        g.reward_kind = 'MAP'
                        AND (
                            u.delivery_id IS NULL
                            OR u.issue_operation_id IS DISTINCT FROM g.fulfillment_operation_id
                            OR u.recipient_player_id IS DISTINCT FROM g.player_id
                            OR u.issue_reason IS DISTINCT FROM 'map.reward'
                            OR i.item_instance_id IS NULL
                            OR i.definition_id IS DISTINCT FROM g.definition_id
                            OR i.original_owner_player_id IS DISTINCT FROM g.player_id
                            OR i.created_by_operation_id IS DISTINCT FROM g.fulfillment_operation_id
                            OR i.created_reason IS DISTINCT FROM 'map.reward'
                            OR m.item_instance_id IS NULL
                            OR g.map_profile IS NULL
                            OR g.map_profile ->> 'difficulty' IS DISTINCT FROM m.difficulty::text
                            OR g.map_profile ->> 'environment_id' IS DISTINCT FROM m.environment_id
                            OR g.map_profile ->> 'enemy_family_id' IS DISTINCT FROM m.enemy_family_id
                            OR g.map_profile ->> 'objective_id' IS DISTINCT FROM m.objective_id
                            OR g.map_profile -> 'modifier_ids' IS DISTINCT FROM m.modifier_ids
                            OR g.map_profile ->> 'generation_seed' IS DISTINCT FROM m.generation_seed::text
                            OR g.map_profile ->> 'generation_version' IS DISTINCT FROM m.generation_version::text
                            OR g.map_profile ->> 'balance_version' IS DISTINCT FROM m.balance_version::text
                            OR g.map_profile ->> 'world_era_id' IS DISTINCT FROM m.world_era_id
                        )
                    )
                  )
                ORDER BY g.fulfilled_at, g.grant_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID grantId = rows.getObject("grant_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "MAP_REWARD_FULFILLMENT_EVIDENCE_MISMATCH",
                            grantId.toString(),
                            "Fulfilled Map reward grant does not match its exact durable delivery/item profile evidence"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
