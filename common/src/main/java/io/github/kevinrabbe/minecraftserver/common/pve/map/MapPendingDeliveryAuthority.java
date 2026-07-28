package io.github.kevinrabbe.minecraftserver.common.pve.map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exactly-once issuance of a new individualized Map into durable pending-delivery custody. */
public final class MapPendingDeliveryAuthority {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final Clock clock;

    public MapPendingDeliveryAuthority(DataSource dataSource, ItemCatalog itemCatalog) {
        this(dataSource, itemCatalog, Clock.systemUTC());
    }

    public MapPendingDeliveryAuthority(DataSource dataSource, ItemCatalog itemCatalog, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MapPendingDeliveryResult createPending(
            UUID operationId,
            String definitionId,
            UUID recipientPlayerId,
            MapRunDefinition mapDefinition,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        Objects.requireNonNull(mapDefinition, "mapDefinition");
        ItemDefinition definition = requireIndividualDefinition(definitionId);
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<PendingMapRow> existing = findByIssueOperation(connection, operationId);
                if (existing.isPresent()) {
                    PendingMapRow previous = existing.orElseThrow();
                    previous.requireSameRequest(
                            definition.definitionId(),
                            recipientPlayerId,
                            mapDefinition,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                requirePlayer(connection, recipientPlayerId);
                requireWorldEra(connection, mapDefinition.worldEraId());
                UUID deliveryId = UUID.randomUUID();
                UUID itemInstanceId = UUID.randomUUID();
                Instant now = clock.instant();

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO pending_unique_deliveries(
                            delivery_id,
                            recipient_player_id,
                            item_instance_id,
                            status,
                            issue_operation_id,
                            issue_reason,
                            created_at
                        ) VALUES (?, ?, ?, 'PENDING', ?, ?, ?)
                        """)) {
                    statement.setObject(1, deliveryId);
                    statement.setObject(2, recipientPlayerId);
                    statement.setObject(3, itemInstanceId);
                    statement.setObject(4, operationId);
                    statement.setString(5, normalizedReason);
                    statement.setTimestamp(6, Timestamp.from(now));
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO item_instances(
                            item_instance_id,
                            definition_id,
                            location_kind,
                            location_id,
                            state_version,
                            original_owner_player_id,
                            created_by_operation_id,
                            created_reason,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, 'PENDING_DELIVERY', ?, 0, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, itemInstanceId);
                    statement.setString(2, definition.definitionId());
                    statement.setObject(3, deliveryId);
                    statement.setObject(4, recipientPlayerId);
                    statement.setObject(5, operationId);
                    statement.setString(6, normalizedReason);
                    statement.setTimestamp(7, Timestamp.from(now));
                    statement.setTimestamp(8, Timestamp.from(now));
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO item_provenance(
                            item_instance_id,
                            sequence_no,
                            operation_id,
                            event_type,
                            from_location_kind,
                            from_location_id,
                            to_location_kind,
                            to_location_id,
                            reason,
                            actor_player_id,
                            created_at
                        ) VALUES (?, 0, ?, 'CREATED', NULL, NULL, 'PENDING_DELIVERY', ?, ?, NULL, ?)
                        """)) {
                    statement.setObject(1, itemInstanceId);
                    statement.setObject(2, operationId);
                    statement.setObject(3, deliveryId);
                    statement.setString(4, normalizedReason);
                    statement.setTimestamp(5, Timestamp.from(now));
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
                        ) VALUES (?, 0, ?, 'ITEM_INSTANCE', ?, 1, 'CREDIT', ?)
                        """)) {
                    statement.setObject(1, operationId);
                    statement.setObject(2, recipientPlayerId);
                    statement.setString(3, itemInstanceId.toString());
                    statement.setString(4, normalizedReason);
                    statement.executeUpdate();
                }

                insertProfile(connection, itemInstanceId, mapDefinition, now);
                MapPendingDeliveryResult result = new MapPendingDeliveryResult(
                        deliveryId,
                        recipientPlayerId,
                        loadProfile(connection, itemInstanceId),
                        0
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private ItemDefinition requireIndividualDefinition(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new MapAuthorityException("pending Map delivery requires INDIVIDUAL definition: " + definitionId);
        }
        return definition;
    }

    private static Optional<PendingMapRow> findByIssueOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT d.delivery_id,
                       d.recipient_player_id,
                       d.issue_reason,
                       i.item_instance_id,
                       i.definition_id,
                       i.state_version,
                       p.difficulty,
                       p.environment_id,
                       p.enemy_family_id,
                       p.objective_id,
                       p.modifier_ids::text AS modifier_ids,
                       p.generation_seed,
                       p.generation_version,
                       p.balance_version,
                       p.world_era_id,
                       p.created_at
                FROM pending_unique_deliveries d
                JOIN item_instances i ON i.item_instance_id = d.item_instance_id
                LEFT JOIN map_item_profiles p ON p.item_instance_id = i.item_instance_id
                WHERE d.issue_operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                if (row.getObject("difficulty") == null) {
                    throw new MapAuthorityException(
                            "operation_id already belongs to a non-Map pending item delivery: " + operationId
                    );
                }
                MapItemProfile profile = readProfile(row);
                return Optional.of(new PendingMapRow(
                        new MapPendingDeliveryResult(
                                row.getObject("delivery_id", UUID.class),
                                row.getObject("recipient_player_id", UUID.class),
                                profile,
                                row.getLong("state_version")
                        ),
                        row.getString("issue_reason")
                ));
            }
        }
    }

    private static void insertProfile(
            Connection connection,
            UUID itemInstanceId,
            MapRunDefinition definition,
            Instant now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO map_item_profiles(
                    item_instance_id,
                    difficulty,
                    environment_id,
                    enemy_family_id,
                    objective_id,
                    modifier_ids,
                    generation_seed,
                    generation_version,
                    balance_version,
                    world_era_id,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setInt(2, definition.difficulty().value());
            statement.setString(3, definition.environmentId());
            statement.setString(4, definition.enemyFamilyId());
            statement.setString(5, definition.objectiveId());
            statement.setString(6, writeStringList(definition.modifierIds()));
            statement.setLong(7, definition.generationSeed());
            statement.setInt(8, definition.generationVersion());
            statement.setInt(9, definition.balanceVersion());
            statement.setString(10, definition.worldEraId());
            statement.setTimestamp(11, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static MapItemProfile loadProfile(Connection connection, UUID itemInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.item_instance_id,
                       i.definition_id,
                       p.difficulty,
                       p.environment_id,
                       p.enemy_family_id,
                       p.objective_id,
                       p.modifier_ids::text AS modifier_ids,
                       p.generation_seed,
                       p.generation_version,
                       p.balance_version,
                       p.world_era_id,
                       p.created_at
                FROM item_instances i
                JOIN map_item_profiles p ON p.item_instance_id = i.item_instance_id
                WHERE i.item_instance_id = ?
                """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("pending Map profile disappeared: " + itemInstanceId);
                }
                return readProfile(row);
            }
        }
    }

    private static MapItemProfile readProfile(ResultSet row) throws SQLException {
        return new MapItemProfile(
                row.getObject("item_instance_id", UUID.class),
                row.getString("definition_id"),
                new MapRunDefinition(
                        new MapDifficulty(row.getInt("difficulty")),
                        row.getString("environment_id"),
                        row.getString("enemy_family_id"),
                        row.getString("objective_id"),
                        readStringList(row.getString("modifier_ids")),
                        row.getLong("generation_seed"),
                        row.getInt("generation_version"),
                        row.getInt("balance_version"),
                        row.getString("world_era_id")
                ),
                row.getTimestamp("created_at").toInstant()
        );
    }

    private static String writeStringList(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new MapAuthorityException("Could not serialize pending Map modifier IDs", exception);
        }
    }

    private static List<String> readStringList(String json) {
        try {
            return List.copyOf(JSON.readValue(json, new TypeReference<List<String>>() { }));
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new MapAuthorityException("Could not parse pending Map modifier IDs", exception);
        }
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown player_id for pending Map delivery: " + playerId);
                }
            }
        }
    }

    private static void requireWorldEra(Connection connection, String worldEraId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM world_eras WHERE era_id = ?")) {
            statement.setString(1, worldEraId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown world era for pending Map delivery: " + worldEraId);
                }
            }
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String normalized = reason.trim();
        if (!REASON_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("reason must be a stable lowercase identifier: " + normalized);
        }
        return normalized;
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record PendingMapRow(MapPendingDeliveryResult result, String reason) {
        private void requireSameRequest(
                String definitionId,
                UUID recipientPlayerId,
                MapRunDefinition mapDefinition,
                String requestedReason,
                UUID operationId
        ) {
            if (!result.recipientPlayerId().equals(recipientPlayerId)
                    || !result.mapProfile().definitionId().equals(definitionId)
                    || !result.mapProfile().runDefinition().equals(mapDefinition)
                    || !reason.equals(requestedReason)) {
                throw new MapAuthorityException(
                        "operation_id reused with a different pending Map request: " + operationId
                );
            }
        }
    }
}
