package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionRepository;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillXpAwardResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Recoverable deterministic fulfillment for immutable resource-harvest entitlements. */
public final class ResourceHarvestFulfillmentRepository {
    private static final String COMMODITY_OPERATION = "RESOURCE_HARVEST_COMMODITY_FULFILL";
    private static final String FULFILL_REASON = "resource.harvest";
    private static final int MAX_SCAN = 1_000;

    private final DataSource dataSource;
    private final SkillProgressionRepository skills;

    public ResourceHarvestFulfillmentRepository(
            DataSource dataSource,
            SkillProgressionCatalog skillCatalog
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.skills = new SkillProgressionRepository(
                dataSource,
                Objects.requireNonNull(skillCatalog, "skillCatalog")
        );
    }

    /** Fulfills commodity and XP using deterministic child operation IDs. Safe after any partial crash. */
    public ResourceHarvestFulfillmentResult fulfill(UUID harvestId) throws SQLException {
        Objects.requireNonNull(harvestId, "harvestId");
        ResourceHarvestEntitlement entitlement = loadEntitlement(harvestId);
        UUID commodityOperationId = deterministicUuid(harvestId, "commodity-operation");
        UUID commodityDeliveryId = deterministicUuid(harvestId, "commodity-delivery");
        issueCommodity(
                commodityOperationId,
                commodityDeliveryId,
                entitlement.playerId(),
                entitlement.commodityDefinitionId(),
                entitlement.commodityQuantity()
        );

        UUID xpOperationId = null;
        SkillXpAwardResult experienceAward = null;
        if (entitlement.skillId() != null) {
            xpOperationId = deterministicUuid(harvestId, "xp-operation");
            experienceAward = skills.awardExperience(
                    xpOperationId,
                    entitlement.playerId(),
                    entitlement.skillId(),
                    entitlement.requestedExperience(),
                    FULFILL_REASON
            );
        }

        Instant completedAt = recordFulfillment(
                entitlement,
                commodityDeliveryId,
                xpOperationId
        );
        return new ResourceHarvestFulfillmentResult(
                entitlement,
                commodityDeliveryId,
                experienceAward,
                completedAt
        );
    }

    /** Bounded recovery scan. Rows remain here until both child authorities were fulfilled and evidence was committed. */
    public List<ResourceHarvestEntitlement> listUnfulfilled(int limit) throws SQLException {
        if (limit <= 0 || limit > MAX_SCAN) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_SCAN);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT h.harvest_id,
                            h.operation_id,
                            h.source_id,
                            h.source_cycle_no,
                            h.player_id,
                            h.commodity_definition_id,
                            h.commodity_quantity,
                            h.skill_id,
                            h.requested_experience,
                            h.created_at
                     FROM resource_harvests h
                     LEFT JOIN resource_harvest_fulfillments f ON f.harvest_id = h.harvest_id
                     WHERE f.harvest_id IS NULL
                     ORDER BY h.created_at, h.harvest_id
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<ResourceHarvestEntitlement> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(entitlementFromRow(rows));
                }
                return List.copyOf(result);
            }
        }
    }

    public ResourceHarvestEntitlement loadEntitlement(UUID harvestId) throws SQLException {
        Objects.requireNonNull(harvestId, "harvestId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT harvest_id,
                            operation_id,
                            source_id,
                            source_cycle_no,
                            player_id,
                            commodity_definition_id,
                            commodity_quantity,
                            skill_id,
                            requested_experience,
                            created_at
                     FROM resource_harvests
                     WHERE harvest_id = ?
                     """)) {
            statement.setObject(1, harvestId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ResourceSourceException("Unknown resource harvest: " + harvestId);
                }
                return entitlementFromRow(row);
            }
        }
    }

    private void issueCommodity(
            UUID operationId,
            UUID deliveryId,
            UUID playerId,
            String commodityDefinitionId,
            long quantity
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedCommodity> processed = findProcessedCommodity(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedCommodity previous = processed.orElseThrow();
                    previous.requireSame(deliveryId, playerId, commodityDefinitionId, quantity, operationId);
                    connection.commit();
                    return;
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO pending_commodity_deliveries(
                            delivery_id,
                            player_id,
                            commodity_definition_id,
                            quantity,
                            source_operation_id,
                            status
                        ) VALUES (?, ?, ?, ?, ?, 'PENDING')
                        """)) {
                    statement.setObject(1, deliveryId);
                    statement.setObject(2, playerId);
                    statement.setString(3, commodityDefinitionId);
                    statement.setLong(4, quantity);
                    statement.setObject(5, operationId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO economic_ledger(
                            operation_id,
                            line_no,
                            player_id,
                            asset_type,
                            asset_id,
                            amount,
                            direction,
                            reason
                        ) VALUES (?, 0, ?, 'COMMODITY', ?, ?, 'CREDIT', ?)
                        """)) {
                    statement.setObject(1, operationId);
                    statement.setObject(2, playerId);
                    statement.setString(3, commodityDefinitionId);
                    statement.setLong(4, quantity);
                    statement.setString(5, FULFILL_REASON);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO processed_operations(operation_id, operation_type, result)
                        VALUES (?, ?, jsonb_build_object(
                            'delivery_id', ?,
                            'player_id', ?,
                            'commodity_definition_id', ?,
                            'quantity', ?
                        ))
                        """)) {
                    statement.setObject(1, operationId);
                    statement.setString(2, COMMODITY_OPERATION);
                    statement.setString(3, deliveryId.toString());
                    statement.setString(4, playerId.toString());
                    statement.setString(5, commodityDefinitionId);
                    statement.setLong(6, quantity);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static Optional<ProcessedCommodity> findProcessedCommodity(
            Connection connection,
            UUID operationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'delivery_id' AS delivery_id,
                       result ->> 'player_id' AS player_id,
                       result ->> 'commodity_definition_id' AS commodity_definition_id,
                       result ->> 'quantity' AS quantity
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                String operationType = row.getString("operation_type");
                if (!COMMODITY_OPERATION.equals(operationType)) {
                    throw new ResourceSourceException(
                            "operation_id " + operationId + " already belongs to " + operationType
                    );
                }
                try {
                    return Optional.of(new ProcessedCommodity(
                            UUID.fromString(requireField(row, "delivery_id")),
                            UUID.fromString(requireField(row, "player_id")),
                            requireField(row, "commodity_definition_id"),
                            Long.parseLong(requireField(row, "quantity"))
                    ));
                } catch (IllegalArgumentException exception) {
                    throw new ResourceSourceException(
                            "Invalid processed resource commodity fulfillment: " + operationId,
                            exception
                    );
                }
            }
        }
    }

    private Instant recordFulfillment(
            ResourceHarvestEntitlement entitlement,
            UUID commodityDeliveryId,
            UUID xpOperationId
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID lockId = deterministicUuid(entitlement.harvestId(), "fulfillment-evidence");
                PostgresOperationLock.lock(connection, lockId);
                Optional<FulfillmentEvidence> existing = findFulfillment(connection, entitlement.harvestId());
                if (existing.isPresent()) {
                    FulfillmentEvidence evidence = existing.orElseThrow();
                    evidence.requireSame(commodityDeliveryId, xpOperationId, entitlement.harvestId());
                    connection.commit();
                    return evidence.completedAt();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO resource_harvest_fulfillments(
                            harvest_id, commodity_delivery_id, xp_operation_id
                        ) VALUES (?, ?, ?)
                        RETURNING completed_at
                        """)) {
                    statement.setObject(1, entitlement.harvestId());
                    statement.setObject(2, commodityDeliveryId);
                    if (xpOperationId == null) {
                        statement.setNull(3, java.sql.Types.OTHER);
                    } else {
                        statement.setObject(3, xpOperationId);
                    }
                    try (ResultSet row = statement.executeQuery()) {
                        row.next();
                        Instant completedAt = row.getTimestamp("completed_at").toInstant();
                        connection.commit();
                        return completedAt;
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static Optional<FulfillmentEvidence> findFulfillment(
            Connection connection,
            UUID harvestId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT commodity_delivery_id, xp_operation_id, completed_at
                FROM resource_harvest_fulfillments
                WHERE harvest_id = ?
                """)) {
            statement.setObject(1, harvestId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new FulfillmentEvidence(
                        row.getObject("commodity_delivery_id", UUID.class),
                        row.getObject("xp_operation_id", UUID.class),
                        row.getTimestamp("completed_at").toInstant()
                ));
            }
        }
    }

    private static ResourceHarvestEntitlement entitlementFromRow(ResultSet row) throws SQLException {
        String rawSkillId = row.getString("skill_id");
        return new ResourceHarvestEntitlement(
                row.getObject("harvest_id", UUID.class),
                row.getObject("operation_id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getLong("source_cycle_no"),
                row.getObject("player_id", UUID.class),
                row.getString("commodity_definition_id"),
                row.getLong("commodity_quantity"),
                rawSkillId == null
                        ? null
                        : new io.github.kevinrabbe.minecraftserver.common.progression.SkillId(rawSkillId),
                row.getLong("requested_experience"),
                row.getTimestamp("created_at").toInstant()
        );
    }

    private static UUID deterministicUuid(UUID harvestId, String purpose) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:resource-harvest:" + harvestId + ":" + purpose)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String requireField(ResultSet row, String field) throws SQLException {
        String value = row.getString(field);
        if (value == null) {
            throw new ResourceSourceException("processed resource fulfillment is missing field: " + field);
        }
        return value;
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record ProcessedCommodity(
            UUID deliveryId,
            UUID playerId,
            String commodityDefinitionId,
            long quantity
    ) {
        private void requireSame(
                UUID expectedDeliveryId,
                UUID expectedPlayerId,
                String expectedDefinitionId,
                long expectedQuantity,
                UUID operationId
        ) {
            if (!deliveryId.equals(expectedDeliveryId)
                    || !playerId.equals(expectedPlayerId)
                    || !commodityDefinitionId.equals(expectedDefinitionId)
                    || quantity != expectedQuantity) {
                throw new ResourceSourceException(
                        "operation_id reused with a different resource commodity fulfillment: " + operationId
                );
            }
        }
    }

    private record FulfillmentEvidence(
            UUID commodityDeliveryId,
            UUID xpOperationId,
            Instant completedAt
    ) {
        private void requireSame(UUID expectedDeliveryId, UUID expectedXpOperationId, UUID harvestId) {
            if (!commodityDeliveryId.equals(expectedDeliveryId)
                    || !Objects.equals(xpOperationId, expectedXpOperationId)) {
                throw new ResourceSourceException(
                        "resource harvest fulfillment evidence changed identity: " + harvestId
                );
            }
        }
    }
}
