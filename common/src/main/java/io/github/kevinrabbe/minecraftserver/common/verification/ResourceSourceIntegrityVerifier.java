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

/** Read-only bounded reconciliation for renewable resource cycles, harvests, fulfillment, and entity-kill evidence. */
public final class ResourceSourceIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public ResourceSourceIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifySourceHeads(connection, issues, maxIssues);
            verifyHarvestOperationEvidence(connection, issues, maxIssues);
            verifyFulfillmentEvidence(connection, issues, maxIssues);
            verifyPreparedClaimEvidence(connection, issues, maxIssues);
            verifyEntityHarvestEvidence(connection, issues, maxIssues);
            verifyKilledSpawnEvidence(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifySourceHeads(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.source_id
                FROM resource_sources s
                WHERE s.state_version IS DISTINCT FROM s.cycle_no
                   OR EXISTS (
                        SELECT 1
                        FROM resource_entity_spawns e
                        WHERE e.source_id = s.source_id
                          AND e.status IN ('PENDING', 'ACTIVE')
                          AND e.source_cycle_no IS DISTINCT FROM s.cycle_no
                   )
                   OR EXISTS (
                        SELECT 1
                        FROM resource_entity_spawns e
                        WHERE e.source_id = s.source_id
                          AND e.status IN ('KILLED', 'CANCELLED', 'EXPIRED')
                          AND e.source_cycle_no >= s.cycle_no
                   )
                ORDER BY s.source_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID sourceId = rows.getObject("source_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "RESOURCE_SOURCE_STATE_MISMATCH",
                            sourceId.toString(),
                            "Renewable resource cycle/version head does not reconcile to its entity-cycle history"
                    ));
                }
            }
        }
    }

    private static void verifyHarvestOperationEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH mismatches AS (
                    SELECT h.operation_id
                    FROM resource_harvests h
                    LEFT JOIN processed_operations op ON op.operation_id = h.operation_id
                    LEFT JOIN player_sessions ps
                      ON ps.network_session_id::text = op.result ->> 'session_id'
                    WHERE op.operation_id IS NULL
                       OR op.operation_type IS DISTINCT FROM 'RESOURCE_SOURCE_HARVEST'
                       OR op.result ->> 'source_id' IS DISTINCT FROM h.source_id::text
                       OR op.result ->> 'reason' IS NULL
                       OR op.result ->> 'reason' !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                       OR CASE
                            WHEN jsonb_typeof(op.result -> 'expected_player_state_version') = 'number'
                            THEN ps.network_session_id IS NULL
                              OR ps.player_id IS DISTINCT FROM h.player_id
                              OR ps.state_version::numeric < (op.result -> 'expected_player_state_version')::numeric
                            ELSE TRUE
                          END
                       OR op.result #>> '{entitlement,harvest_id}' IS DISTINCT FROM h.harvest_id::text
                       OR op.result #>> '{entitlement,operation_id}' IS DISTINCT FROM h.operation_id::text
                       OR op.result #>> '{entitlement,source_id}' IS DISTINCT FROM h.source_id::text
                       OR op.result #>> '{entitlement,source_cycle_no}' IS DISTINCT FROM h.source_cycle_no::text
                       OR op.result #>> '{entitlement,player_id}' IS DISTINCT FROM h.player_id::text
                       OR op.result #>> '{entitlement,commodity_definition_id}' IS DISTINCT FROM h.commodity_definition_id
                       OR op.result #>> '{entitlement,commodity_quantity}' IS DISTINCT FROM h.commodity_quantity::text
                       OR op.result #>> '{entitlement,skill_id}' IS DISTINCT FROM h.skill_id
                       OR op.result #>> '{entitlement,requested_experience}' IS DISTINCT FROM h.requested_experience::text
                    UNION
                    SELECT op.operation_id
                    FROM processed_operations op
                    LEFT JOIN resource_harvests h ON h.operation_id = op.operation_id
                    WHERE op.operation_type = 'RESOURCE_SOURCE_HARVEST'
                      AND h.harvest_id IS NULL
                )
                SELECT operation_id
                FROM mismatches
                ORDER BY operation_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "RESOURCE_HARVEST_OPERATION_EVIDENCE_MISMATCH",
                            operationId.toString(),
                            "Resource harvest does not reconcile to its exact processed operation/session entitlement evidence"
                    ));
                }
            }
        }
    }

    private static void verifyFulfillmentEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT h.harvest_id
                FROM resource_harvest_fulfillments f
                JOIN resource_harvests h ON h.harvest_id = f.harvest_id
                LEFT JOIN pending_commodity_deliveries c ON c.delivery_id = f.commodity_delivery_id
                LEFT JOIN processed_operations cop ON cop.operation_id = c.source_operation_id
                LEFT JOIN skill_xp_awards x ON x.operation_id = f.xp_operation_id
                WHERE (
                        h.commodity_definition_id IS NULL
                        AND (
                            h.commodity_quantity IS DISTINCT FROM 0
                            OR f.commodity_delivery_id IS NOT NULL
                        )
                    )
                   OR (
                        h.commodity_definition_id IS NOT NULL
                        AND (
                            f.commodity_delivery_id IS NULL
                            OR c.delivery_id IS NULL
                            OR c.player_id IS DISTINCT FROM h.player_id
                            OR c.commodity_definition_id IS DISTINCT FROM h.commodity_definition_id
                            OR c.quantity IS DISTINCT FROM h.commodity_quantity
                            OR cop.operation_id IS NULL
                            OR cop.operation_type IS DISTINCT FROM 'RESOURCE_HARVEST_COMMODITY_FULFILL'
                            OR cop.result ->> 'delivery_id' IS DISTINCT FROM f.commodity_delivery_id::text
                            OR cop.result ->> 'player_id' IS DISTINCT FROM h.player_id::text
                            OR cop.result ->> 'commodity_definition_id' IS DISTINCT FROM h.commodity_definition_id
                            OR cop.result ->> 'quantity' IS DISTINCT FROM h.commodity_quantity::text
                        )
                    )
                   OR (h.skill_id IS NULL AND f.xp_operation_id IS NOT NULL)
                   OR (h.skill_id IS NOT NULL AND (
                        f.xp_operation_id IS NULL
                        OR x.operation_id IS NULL
                        OR x.player_id IS DISTINCT FROM h.player_id
                        OR x.skill_id IS DISTINCT FROM h.skill_id
                        OR x.requested_experience IS DISTINCT FROM h.requested_experience
                        OR x.reason IS DISTINCT FROM 'resource.harvest'
                   ))
                ORDER BY h.created_at, h.harvest_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID harvestId = rows.getObject("harvest_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "RESOURCE_HARVEST_FULFILLMENT_EVIDENCE_MISMATCH",
                            harvestId.toString(),
                            "Fulfilled resource harvest does not match its optional commodity-delivery and XP evidence"
                    ));
                }
            }
        }
    }

    private static void verifyPreparedClaimEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.operation_id
                FROM resource_entity_kill_claims c
                LEFT JOIN resource_entity_spawns e ON e.spawn_id = c.spawn_id
                WHERE e.spawn_id IS NULL
                   OR c.entity_uuid IS DISTINCT FROM e.entity_uuid
                ORDER BY c.prepared_at, c.operation_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "RESOURCE_ENTITY_CLAIM_EVIDENCE_MISMATCH",
                            operationId.toString(),
                            "Managed-entity kill claim does not match the exact prepared runtime entity"
                    ));
                }
            }
        }
    }

    private static void verifyEntityHarvestEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT h.harvest_id
                FROM resource_harvests h
                JOIN resource_entity_sources marker ON marker.source_id = h.source_id
                LEFT JOIN resource_entity_kill_claims c ON c.operation_id = h.operation_id
                LEFT JOIN resource_entity_spawns e ON e.spawn_id = c.spawn_id
                WHERE c.operation_id IS NULL
                   OR e.spawn_id IS NULL
                   OR c.entity_uuid IS DISTINCT FROM e.entity_uuid
                   OR e.source_id IS DISTINCT FROM h.source_id
                   OR e.source_cycle_no IS DISTINCT FROM h.source_cycle_no
                   OR e.status IS DISTINCT FROM 'KILLED'
                   OR e.killer_player_id IS DISTINCT FROM h.player_id
                ORDER BY h.created_at, h.harvest_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID harvestId = rows.getObject("harvest_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "RESOURCE_ENTITY_HARVEST_EVIDENCE_MISMATCH",
                            harvestId.toString(),
                            "Entity-bound harvest does not reconcile to its exact kill claim and killed source cycle"
                    ));
                }
            }
        }
    }

    private static void verifyKilledSpawnEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.spawn_id
                FROM resource_entity_spawns e
                LEFT JOIN resource_entity_kill_claims c ON c.spawn_id = e.spawn_id
                LEFT JOIN resource_harvests h ON h.operation_id = c.operation_id
                WHERE e.status = 'KILLED'
                  AND (
                    c.operation_id IS NULL
                    OR c.entity_uuid IS DISTINCT FROM e.entity_uuid
                    OR h.harvest_id IS NULL
                    OR h.source_id IS DISTINCT FROM e.source_id
                    OR h.source_cycle_no IS DISTINCT FROM e.source_cycle_no
                    OR h.player_id IS DISTINCT FROM e.killer_player_id
                  )
                ORDER BY e.resolved_at, e.spawn_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID spawnId = rows.getObject("spawn_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "RESOURCE_ENTITY_KILL_EVIDENCE_MISMATCH",
                            spawnId.toString(),
                            "Killed managed entity does not reconcile to its exact claim and resource harvest"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
