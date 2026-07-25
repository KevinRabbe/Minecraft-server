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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable exactly-once reward entitlements for completed Map runs. */
public final class MapRewardSettlementRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_GRANTS_PER_RUN = 256;

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final MapRewardResolver resolver;
    private final Clock clock;

    public MapRewardSettlementRepository(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            MapRewardResolver resolver
    ) {
        this(dataSource, itemCatalog, resolver, Clock.systemUTC());
    }

    public MapRewardSettlementRepository(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            MapRewardResolver resolver,
            Clock clock
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (resolver.version() < 0) {
            throw new IllegalArgumentException("Map reward resolver version must be >= 0");
        }
    }

    public MapRewardSettlementResult settle(
            UUID operationId,
            UUID runId,
            long expectedRunStateVersion
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(runId, "runId");
        if (expectedRunStateVersion < 0) {
            throw new IllegalArgumentException("expectedRunStateVersion must be >= 0");
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<MapRewardSettlementResult> processed = findByOperation(connection, operationId);
                if (processed.isPresent()) {
                    MapRewardSettlementResult previous = processed.orElseThrow();
                    if (!previous.runId().equals(runId)) {
                        throw new MapAuthorityException(
                                "reward settlement operation_id reused for a different Map run: " + operationId
                        );
                    }
                    connection.commit();
                    return previous;
                }

                CompletedRun current = lockCompletedRun(connection, runId);
                if (current.stateVersion() != expectedRunStateVersion) {
                    throw new MapAuthorityException("stale Map run state_version during reward settlement: " + runId);
                }
                if (current.rewardOperationId() != null) {
                    throw new MapAuthorityException("Map run already has a different reward settlement: " + runId);
                }

                List<UUID> participants = readParticipants(connection, runId);
                if (participants.isEmpty()) {
                    throw new MapAuthorityException("completed Map run has no participants: " + runId);
                }
                MapRunSnapshot run = current.snapshot();
                List<MapRewardDefinition> definitions = validateRewardDefinitions(
                        resolver.resolve(run, participants),
                        participants
                );
                if (definitions.isEmpty()) {
                    throw new MapAuthorityException("successful Map reward resolver produced no grants");
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE map_runs
                        SET reward_operation_id = ?
                        WHERE run_id = ?
                          AND status = 'COMPLETED'
                          AND state_version = ?
                          AND reward_operation_id IS NULL
                        """)) {
                    statement.setObject(1, operationId);
                    statement.setObject(2, runId);
                    statement.setLong(3, expectedRunStateVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new MapAuthorityException("Map reward authority changed concurrently: " + runId);
                    }
                }

                Instant settledAt = clock.instant();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO map_reward_settlements(
                            run_id, settlement_operation_id, resolver_version, settled_at
                        ) VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, runId);
                    statement.setObject(2, operationId);
                    statement.setInt(3, resolver.version());
                    statement.setTimestamp(4, Timestamp.from(settledAt));
                    statement.executeUpdate();
                }

                for (int ordinal = 0; ordinal < definitions.size(); ordinal++) {
                    insertGrant(connection, runId, ordinal, definitions.get(ordinal), settledAt);
                }

                MapRewardSettlementResult result = loadSettlement(connection, runId);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public MapRewardSettlementResult load(UUID runId) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = dataSource.getConnection()) {
            return loadSettlement(connection, runId);
        }
    }

    public List<MapRewardGrantSnapshot> listPending(UUID playerId, int limit) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT grant_id,
                            run_id,
                            player_id,
                            ordinal,
                            reward_kind,
                            definition_id,
                            quantity,
                            map_profile::text AS map_profile,
                            status,
                            fulfillment_operation_id,
                            created_at,
                            fulfilled_at
                     FROM map_reward_grants
                     WHERE player_id = ? AND status = 'PENDING'
                     ORDER BY created_at ASC, run_id ASC, ordinal ASC
                     LIMIT ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<MapRewardGrantSnapshot> grants = new ArrayList<>();
                while (rows.next()) {
                    grants.add(readGrant(rows));
                }
                return List.copyOf(grants);
            }
        }
    }

    private List<MapRewardDefinition> validateRewardDefinitions(
            List<MapRewardDefinition> raw,
            List<UUID> participants
    ) {
        Objects.requireNonNull(raw, "Map reward resolver result");
        if (raw.size() > MAX_GRANTS_PER_RUN) {
            throw new MapAuthorityException("Map reward resolver exceeded grant safety ceiling " + MAX_GRANTS_PER_RUN);
        }
        Set<UUID> participantSet = Set.copyOf(participants);
        List<MapRewardDefinition> validated = new ArrayList<>(raw.size());
        for (MapRewardDefinition reward : raw) {
            MapRewardDefinition nonNull = Objects.requireNonNull(reward, "Map reward resolver returned null grant");
            if (!participantSet.contains(nonNull.playerId())) {
                throw new MapAuthorityException("Map reward recipient is not a run participant: " + nonNull.playerId());
            }
            ItemDefinition definition = itemCatalog.require(nonNull.definitionId());
            if (nonNull.kind() == MapRewardKind.COMMODITY) {
                if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                    throw new MapAuthorityException("COMMODITY Map reward requires commodity item definition");
                }
            } else {
                if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
                    throw new MapAuthorityException(nonNull.kind() + " Map reward requires individual item definition");
                }
            }
            validated.add(nonNull);
        }
        return List.copyOf(validated);
    }

    private static CompletedRun lockCompletedRun(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_map_item_id,
                       status,
                       difficulty,
                       environment_id,
                       enemy_family_id,
                       objective_id,
                       modifier_ids::text AS modifier_ids,
                       generation_seed,
                       generation_version,
                       balance_version,
                       world_era_id,
                       state_version,
                       reward_operation_id,
                       created_at,
                       started_at,
                       finished_at
                FROM map_runs
                WHERE run_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown Map run: " + runId);
                }
                MapRunStatus status = MapRunStatus.valueOf(row.getString("status"));
                if (status != MapRunStatus.COMPLETED) {
                    throw new MapAuthorityException("Map rewards require COMPLETED run: " + runId);
                }
                Timestamp started = row.getTimestamp("started_at");
                Timestamp finished = row.getTimestamp("finished_at");
                MapRunSnapshot snapshot = new MapRunSnapshot(
                        runId,
                        row.getObject("source_map_item_id", UUID.class),
                        status,
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
                        row.getLong("state_version"),
                        row.getTimestamp("created_at").toInstant(),
                        started == null ? null : started.toInstant(),
                        finished == null ? null : finished.toInstant()
                );
                return new CompletedRun(
                        snapshot,
                        row.getLong("state_version"),
                        row.getObject("reward_operation_id", UUID.class)
                );
            }
        }
    }

    private static List<UUID> readParticipants(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id
                FROM map_run_participants
                WHERE run_id = ?
                ORDER BY player_id ASC
                """)) {
            statement.setObject(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                List<UUID> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getObject("player_id", UUID.class));
                }
                return List.copyOf(values);
            }
        }
    }

    private static void insertGrant(
            Connection connection,
            UUID runId,
            int ordinal,
            MapRewardDefinition reward,
            Instant settledAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO map_reward_grants(
                    grant_id,
                    run_id,
                    player_id,
                    ordinal,
                    reward_kind,
                    definition_id,
                    quantity,
                    map_profile,
                    status,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, runId);
            statement.setObject(3, reward.playerId());
            statement.setInt(4, ordinal);
            statement.setString(5, reward.kind().name());
            statement.setString(6, reward.definitionId());
            statement.setLong(7, reward.quantity());
            if (reward.successorMapDefinition() == null) {
                statement.setNull(8, java.sql.Types.VARCHAR);
            } else {
                statement.setString(8, writeMapDefinition(reward.successorMapDefinition()));
            }
            statement.setTimestamp(9, Timestamp.from(settledAt));
            statement.executeUpdate();
        }
    }

    private static Optional<MapRewardSettlementResult> findByOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT run_id
                FROM map_reward_settlements
                WHERE settlement_operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(loadSettlement(connection, row.getObject("run_id", UUID.class)))
                        : Optional.empty();
            }
        }
    }

    private static MapRewardSettlementResult loadSettlement(Connection connection, UUID runId) throws SQLException {
        UUID operationId;
        int resolverVersion;
        Instant settledAt;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT settlement_operation_id, resolver_version, settled_at
                FROM map_reward_settlements
                WHERE run_id = ?
                """)) {
            statement.setObject(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Map run has no reward settlement: " + runId);
                }
                operationId = row.getObject("settlement_operation_id", UUID.class);
                resolverVersion = row.getInt("resolver_version");
                settledAt = row.getTimestamp("settled_at").toInstant();
            }
        }
        return new MapRewardSettlementResult(
                runId,
                operationId,
                resolverVersion,
                settledAt,
                readGrants(connection, runId)
        );
    }

    private static List<MapRewardGrantSnapshot> readGrants(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT grant_id,
                       run_id,
                       player_id,
                       ordinal,
                       reward_kind,
                       definition_id,
                       quantity,
                       map_profile::text AS map_profile,
                       status,
                       fulfillment_operation_id,
                       created_at,
                       fulfilled_at
                FROM map_reward_grants
                WHERE run_id = ?
                ORDER BY ordinal ASC
                """)) {
            statement.setObject(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                List<MapRewardGrantSnapshot> grants = new ArrayList<>();
                while (rows.next()) {
                    grants.add(readGrant(rows));
                }
                return List.copyOf(grants);
            }
        }
    }

    private static MapRewardGrantSnapshot readGrant(ResultSet row) throws SQLException {
        String mapProfile = row.getString("map_profile");
        Timestamp fulfilled = row.getTimestamp("fulfilled_at");
        return new MapRewardGrantSnapshot(
                row.getObject("grant_id", UUID.class),
                row.getObject("run_id", UUID.class),
                row.getObject("player_id", UUID.class),
                row.getInt("ordinal"),
                MapRewardKind.valueOf(row.getString("reward_kind")),
                row.getString("definition_id"),
                row.getLong("quantity"),
                mapProfile == null ? null : readMapDefinition(mapProfile),
                MapRewardGrantStatus.valueOf(row.getString("status")),
                row.getObject("fulfillment_operation_id", UUID.class),
                row.getTimestamp("created_at").toInstant(),
                fulfilled == null ? null : fulfilled.toInstant()
        );
    }

    private static String writeMapDefinition(MapRunDefinition definition) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "difficulty", definition.difficulty().value(),
                    "environment_id", definition.environmentId(),
                    "enemy_family_id", definition.enemyFamilyId(),
                    "objective_id", definition.objectiveId(),
                    "modifier_ids", definition.modifierIds(),
                    "generation_seed", definition.generationSeed(),
                    "generation_version", definition.generationVersion(),
                    "balance_version", definition.balanceVersion(),
                    "world_era_id", definition.worldEraId()
            ));
        } catch (JsonProcessingException exception) {
            throw new MapAuthorityException("Could not serialize successor Map reward profile", exception);
        }
    }

    private static MapRunDefinition readMapDefinition(String json) {
        try {
            Map<String, Object> value = JSON.readValue(json, new TypeReference<>() { });
            @SuppressWarnings("unchecked")
            List<String> modifiers = ((List<Object>) value.get("modifier_ids")).stream()
                    .map(Object::toString)
                    .toList();
            return new MapRunDefinition(
                    new MapDifficulty(((Number) value.get("difficulty")).intValue()),
                    Objects.toString(value.get("environment_id")),
                    Objects.toString(value.get("enemy_family_id")),
                    Objects.toString(value.get("objective_id")),
                    modifiers,
                    ((Number) value.get("generation_seed")).longValue(),
                    ((Number) value.get("generation_version")).intValue(),
                    ((Number) value.get("balance_version")).intValue(),
                    Objects.toString(value.get("world_era_id"))
            );
        } catch (JsonProcessingException | ClassCastException | NullPointerException exception) {
            throw new MapAuthorityException("Could not parse successor Map reward profile", exception);
        }
    }

    private static List<String> readStringList(String json) {
        try {
            return List.copyOf(JSON.readValue(json, new TypeReference<List<String>>() { }));
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new MapAuthorityException("Could not parse Map modifier IDs", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record CompletedRun(MapRunSnapshot snapshot, long stateVersion, UUID rewardOperationId) {
    }
}
