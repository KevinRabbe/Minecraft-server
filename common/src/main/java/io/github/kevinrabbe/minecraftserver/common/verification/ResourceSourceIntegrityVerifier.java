package io.github.kevinrabbe.minecraftserver.common.verification;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
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
    private static final String HARVEST_OPERATION = "RESOURCE_SOURCE_HARVEST";
    private static final String COMMODITY_FULFILL_OPERATION = "RESOURCE_HARVEST_COMMODITY_FULFILL";
    private static final String RESOURCE_XP_REASON = "resource.harvest";

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
                       OR op.result ->> 'reason' !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                       OR jsonb_typeof(op.result -> 'expected_player_state_version') IS DISTINCT FROM 'number'
                       OR ps.network_session_id IS NULL
                       OR ps.player_id IS DISTINCT FROM h.player_id
                       OR ps.state_version::numeric < (op.result -> 'expected_player_state_version')::numeric
                       OR op.result #>> '{entitlement,harvest_id}' IS DISTINCT FROM h.harvest_id::text
                       OR op.result #>> '{entitlement,operation_id}' IS DISTINCT FROM h.operation_id::text
                       OR op.result #>> '{entitlement,source_id}' IS DISTINCT FROM h.source_id::text
                       OR op.result #>> '{entitlement,source_cycle_no}' IS DISTINCT FROM h.source_cycle_no::text
                       OR op.result #>> '{entitlement,player_id}' IS DISTINCT FROM h.player_id::text
                       OR op.result #>> '{entitlement,commodity_definition_id}' IS DISTINCT FROM h.commodity_definition_id
                       OR op.result #>> '{entitlement,commodity_quantity}' IS DISTINCT FROM h.commodity_quantity::text
                       OR (op.result #> '{entitlement,skill_id}') IS DISTINCT FROM to_jsonb(h.skill_id)
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
                SELECT h.harvest_id,
                       h.player_id,
                       h.commodity_definition_id,
                       h.commodity_quantity,
                       h.skill_id,
                       h.requested_experience,
                       f.commodity_delivery_id,
                       f.xp_operation_id,
                       c.delivery_id AS observed_delivery_id,
                       c.player_id AS delivery_player_id,
                       c.commodity_definition_id AS delivery_definition_id,
                       c.quantity AS delivery_quantity,
                       c.source_operation_id AS commodity_operation_id,
                       cop.operation_type AS commodity_operation_type,
                       cop.result ->> 'delivery_id' AS result_delivery_id,
                       cop.result ->> 'player_id' AS result_player_id,
                       cop.result ->> 'commodity_definition_id' AS result_definition_id,
                       cop.result ->> 'quantity' AS result_quantity,
                       x.operation_id AS xp_evidence_operation_id,
                       x.player_id AS xp_player_id,
                       x.skill_id AS xp_skill_id,
                       x.requested_experience AS xp_requested_experience,
                       x.reason AS xp_reason
                FROM resource_harvest_fulfillments f
                JOIN resource_harvests h ON h.harvest_id = f.harvest_id
                LEFT JOIN pending_commodity_deliveries c ON c.delivery_id = f.commodity_delivery_id
                LEFT JOIN processed_operations cop ON cop.operation_id = c.source_operation_id
                LEFT JOIN skill_xp_awards x ON x.operation_id = f.xp_operation_id
                ORDER BY h.created_at, h.harvest_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID harvestId = rows.getObject("harvest_id", UUID.class);
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    String definitionId = rows.getString("commodity_definition_id");
                    long quantity = rows.getLong("commodity_quantity");
                    String skillId = rows.getString("skill_id");
                    long requestedExperience = rows.getLong("requested_experience");
                    UUID expectedCommodityOperation = childOperation(harvestId, "commodity-operation");
                    UUID expectedDelivery = childOperation(harvestId, "commodity-delivery");
                    UUID expectedXpOperation = skillId == null ? null : childOperation(harvestId, "xp-operation");

                    UUID recordedDelivery = rows.getObject("commodity_delivery_id", UUID.class);
                    UUID observedDelivery = rows.getObject("observed_delivery_id", UUID.class);
                    UUID deliveryPlayer = rows.getObject("delivery_player_id", UUID.class);
                    String deliveryDefinition = rows.getString("delivery_definition_id");
                    long deliveryQuantity = rows.getLong("delivery_quantity");
                    boolean deliveryQuantityWasNull = rows.wasNull();
                    UUID commodityOperation = rows.getObject("commodity_operation_id", UUID.class);
                    String operationType = rows.getString("commodity_operation_type");
                    UUID xpOperation = rows.getObject("xp_operation_id", UUID.class);
                    UUID xpEvidenceOperation = rows.getObject("xp_evidence_operation_id", UUID.class);
                    UUID xpPlayer = rows.getObject("xp_player_id", UUID.class);
                    String xpSkill = rows.getString("xp_skill_id");
                    long xpRequested = rows.getLong("xp_requested_experience");
                    boolean xpRequestedWasNull = rows.wasNull();
                    String xpReason = rows.getString("xp_reason");

                    boolean commodityMismatch = !expectedDelivery.equals(recordedDelivery)
                            || !expectedDelivery.equals(observedDelivery)
                            || !playerId.equals(deliveryPlayer)
                            || !definitionId.equals(deliveryDefinition)
                            || deliveryQuantityWasNull
                            || quantity != deliveryQuantity
                            || !expectedCommodityOperation.equals(commodityOperation)
                            || !COMMODITY_FULFILL_OPERATION.equals(operationType)
                            || !expectedDelivery.toString().equals(rows.getString("result_delivery_id"))
                            || !playerId.toString().equals(rows.getString("result_player_id"))
                            || !definitionId.equals(rows.getString("result_definition_id"))
                            || !Long.toString(quantity).equals(rows.getString("result_quantity"));

                    boolean xpMismatch;
                    if (skillId == null) {
                        xpMismatch = xpOperation != null || xpEvidenceOperation != null;
                    } else {
                        xpMismatch = !expectedXpOperation.equals(xpOperation)
                                || !expectedXpOperation.equals(xpEvidenceOperation)
                                || !playerId.equals(xpPlayer)
                                || !skillId.equals(xpSkill)
                                || xpRequestedWasNull
                                || requestedExperience != xpRequested
                                || !RESOURCE_XP_REASON.equals(xpReason);
                    }

                    if (commodityMismatch || xpMismatch) {
                        issues.add(new IntegrityIssue(
                                IntegritySeverity.CRITICAL,
                                "RESOURCE_HARVEST_FULFILLMENT_EVIDENCE_MISMATCH",
                                harvestId.toString(),
                                "Fulfilled resource harvest does not match its deterministic commodity-delivery/XP evidence"
                        ));
                    }
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
                SELECT h.harvest_id,
                       h.operation_id,
                       h.source_id,
                       h.source_cycle_no,
                       h.player_id,
                       c.operation_id AS claim_operation_id,
                       c.spawn_id,
                       c.entity_uuid AS claim_entity_uuid,
                       e.source_id AS spawn_source_id,
                       e.source_cycle_no AS spawn_cycle_no,
                       e.status AS spawn_status,
                       e.entity_uuid AS spawn_entity_uuid,
                       e.killer_player_id
                FROM resource_harvests h
                JOIN resource_entity_sources marker ON marker.source_id = h.source_id
                LEFT JOIN resource_entity_kill_claims c ON c.operation_id = h.operation_id
                LEFT JOIN resource_entity_spawns e ON e.spawn_id = c.spawn_id
                ORDER BY h.created_at, h.harvest_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID harvestId = rows.getObject("harvest_id", UUID.class);
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    UUID sourceId = rows.getObject("source_id", UUID.class);
                    long cycle = rows.getLong("source_cycle_no");
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    UUID claimOperation = rows.getObject("claim_operation_id", UUID.class);
                    UUID spawnId = rows.getObject("spawn_id", UUID.class);
                    UUID claimEntity = rows.getObject("claim_entity_uuid", UUID.class);
                    UUID spawnSource = rows.getObject("spawn_source_id", UUID.class);
                    long spawnCycle = rows.getLong("spawn_cycle_no");
                    boolean spawnCycleWasNull = rows.wasNull();
                    String spawnStatus = rows.getString("spawn_status");
                    UUID spawnEntity = rows.getObject("spawn_entity_uuid", UUID.class);
                    UUID killer = rows.getObject("killer_player_id", UUID.class);

                    boolean mismatch = !operationId.equals(claimOperation)
                            || spawnId == null
                            || !killOperation(spawnId).equals(operationId)
                            || !Objects.equals(claimEntity, spawnEntity)
                            || !sourceId.equals(spawnSource)
                            || spawnCycleWasNull
                            || cycle != spawnCycle
                            || !"KILLED".equals(spawnStatus)
                            || !playerId.equals(killer);
                    if (mismatch) {
                        issues.add(new IntegrityIssue(
                                IntegritySeverity.CRITICAL,
                                "RESOURCE_ENTITY_HARVEST_EVIDENCE_MISMATCH",
                                harvestId.toString(),
                                "Entity-bound harvest does not reconcile to its deterministic kill claim and killed spawn"
                        ));
                    }
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
                SELECT e.spawn_id,
                       e.source_id,
                       e.source_cycle_no,
                       e.entity_uuid,
                       e.killer_player_id,
                       c.operation_id,
                       c.entity_uuid AS claim_entity_uuid,
                       h.harvest_id,
                       h.source_id AS harvest_source_id,
                       h.source_cycle_no AS harvest_cycle_no,
                       h.player_id AS harvest_player_id
                FROM resource_entity_spawns e
                LEFT JOIN resource_entity_kill_claims c ON c.spawn_id = e.spawn_id
                LEFT JOIN resource_harvests h ON h.operation_id = c.operation_id
                WHERE e.status = 'KILLED'
                ORDER BY e.resolved_at, e.spawn_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID spawnId = rows.getObject("spawn_id", UUID.class);
                    UUID sourceId = rows.getObject("source_id", UUID.class);
                    long cycle = rows.getLong("source_cycle_no");
                    UUID entityUuid = rows.getObject("entity_uuid", UUID.class);
                    UUID killer = rows.getObject("killer_player_id", UUID.class);
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    UUID claimEntity = rows.getObject("claim_entity_uuid", UUID.class);
                    UUID harvestId = rows.getObject("harvest_id", UUID.class);
                    UUID harvestSource = rows.getObject("harvest_source_id", UUID.class);
                    long harvestCycle = rows.getLong("harvest_cycle_no");
                    boolean harvestCycleWasNull = rows.wasNull();
                    UUID harvestPlayer = rows.getObject("harvest_player_id", UUID.class);

                    boolean mismatch = operationId == null
                            || !killOperation(spawnId).equals(operationId)
                            || !Objects.equals(entityUuid, claimEntity)
                            || harvestId == null
                            || !sourceId.equals(harvestSource)
                            || harvestCycleWasNull
                            || cycle != harvestCycle
                            || !killer.equals(harvestPlayer);
                    if (mismatch) {
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
    }

    private static UUID childOperation(UUID harvestId, String purpose) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:resource-harvest:" + harvestId + ":" + purpose)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static UUID killOperation(UUID spawnId) {
        return UUID.nameUUIDFromBytes(
                ("resource-entity-kill:" + spawnId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
