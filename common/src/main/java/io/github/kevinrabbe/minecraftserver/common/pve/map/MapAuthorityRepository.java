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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL authority for individualized Map items and their persistent run lifecycle.
 *
 * <p>Opening is one transaction: the exact Map item is fenced, consumed to DESTROYED custody, provenance/ledger
 * evidence is appended, and the one run identity is created from the immutable Map profile. Runtime encounters remain
 * disposable; this repository owns the persistent interpretation and clear evidence.</p>
 */
public final class MapAuthorityRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final int MAX_PARTICIPANTS = 64;

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final Clock clock;

    public MapAuthorityRepository(DataSource dataSource, ItemCatalog itemCatalog) {
        this(dataSource, itemCatalog, Clock.systemUTC());
    }

    public MapAuthorityRepository(DataSource dataSource, ItemCatalog itemCatalog, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Mints one individualized Map item and its immutable challenge profile exactly once. */
    public MapItemProfile issueMap(
            UUID operationId,
            String definitionId,
            UUID ownerPlayerId,
            MapRunDefinition runDefinition,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(runDefinition, "runDefinition");
        ItemDefinition itemDefinition = requireIndividualDefinition(definitionId);
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<IssuedMapRow> existing = findIssuedMap(connection, operationId);
                if (existing.isPresent()) {
                    IssuedMapRow previous = existing.orElseThrow();
                    previous.requireSameRequest(
                            itemDefinition.definitionId(),
                            ownerPlayerId,
                            runDefinition,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.profile();
                }

                requirePlayer(connection, ownerPlayerId);
                requireWorldEra(connection, runDefinition.worldEraId());

                UUID itemInstanceId = UUID.randomUUID();
                Instant now = clock.instant();
                insertItemInstance(
                        connection,
                        itemInstanceId,
                        itemDefinition.definitionId(),
                        ownerPlayerId,
                        operationId,
                        normalizedReason,
                        now
                );
                insertItemProvenance(
                        connection,
                        itemInstanceId,
                        0,
                        operationId,
                        "CREATED",
                        "PLAYER_INVENTORY",
                        ownerPlayerId,
                        normalizedReason,
                        ownerPlayerId,
                        null,
                        null,
                        now
                );
                insertItemLedger(
                        connection,
                        operationId,
                        0,
                        ownerPlayerId,
                        itemInstanceId,
                        "CREDIT",
                        normalizedReason
                );
                insertMapProfile(connection, itemInstanceId, runDefinition, now);

                MapItemProfile result = loadMapProfile(connection, itemInstanceId);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public MapItemProfile loadMapProfile(UUID itemInstanceId) throws SQLException {
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        try (Connection connection = dataSource.getConnection()) {
            return loadMapProfile(connection, itemInstanceId);
        }
    }

    /** Atomically consumes the owned Map item and creates its single persistent run identity. */
    public UUID openMap(
            UUID operationId,
            UUID mapItemInstanceId,
            UUID playerId,
            long expectedItemStateVersion,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(mapItemInstanceId, "mapItemInstanceId");
        Objects.requireNonNull(playerId, "playerId");
        if (expectedItemStateVersion < 0) {
            throw new IllegalArgumentException("expectedItemStateVersion must be >= 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<OpenedRunEvidence> processed = findRunByOpenOperation(connection, operationId);
                if (processed.isPresent()) {
                    OpenedRunEvidence previous = processed.orElseThrow();
                    previous.requireSameRequest(
                            mapItemInstanceId,
                            playerId,
                            expectedItemStateVersion,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.runId();
                }

                MapItemAuthorityRow item = lockMapItem(connection, mapItemInstanceId);
                requireIndividualDefinition(item.profile().definitionId());
                if (!"PLAYER_INVENTORY".equals(item.locationKind())
                        || !playerId.equals(item.locationId())) {
                    throw new MapAuthorityException("Map item is not owned in the opening player's inventory");
                }
                if (item.stateVersion() != expectedItemStateVersion) {
                    throw new MapAuthorityException(
                            "Stale Map item state_version: expected " + expectedItemStateVersion
                                    + " but authoritative version is " + item.stateVersion()
                    );
                }

                long nextItemVersion = incrementVersion(item.stateVersion(), "Map item", mapItemInstanceId);
                Instant now = clock.instant();
                consumeMapItem(
                        connection,
                        mapItemInstanceId,
                        item.stateVersion(),
                        nextItemVersion,
                        now
                );
                insertItemProvenance(
                        connection,
                        mapItemInstanceId,
                        nextItemVersion,
                        operationId,
                        "DESTROYED",
                        "DESTROYED",
                        null,
                        normalizedReason,
                        playerId,
                        "PLAYER_INVENTORY",
                        playerId,
                        now
                );
                insertItemLedger(
                        connection,
                        operationId,
                        0,
                        playerId,
                        mapItemInstanceId,
                        "DEBIT",
                        normalizedReason
                );

                UUID runId = UUID.randomUUID();
                insertRun(
                        connection,
                        runId,
                        mapItemInstanceId,
                        item.profile().runDefinition(),
                        operationId,
                        playerId,
                        expectedItemStateVersion,
                        normalizedReason,
                        now
                );
                connection.commit();
                return runId;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public MapRunSnapshot loadRun(UUID runId) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = dataSource.getConnection()) {
            return readRun(connection, runId, false);
        }
    }

    /** Locks the complete participant set and transitions CREATED -> ACTIVE exactly once. */
    public void startRun(
            UUID operationId,
            UUID runId,
            long expectedRunStateVersion,
            List<UUID> participantPlayerIds,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(runId, "runId");
        if (expectedRunStateVersion < 0) {
            throw new IllegalArgumentException("expectedRunStateVersion must be >= 0");
        }
        List<UUID> participants = normalizeParticipants(participantPlayerIds);
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<RunActionEvidence> processed = findByStartOperation(connection, operationId);
                if (processed.isPresent()) {
                    RunActionEvidence previous = processed.orElseThrow();
                    previous.requireSame(runId, expectedRunStateVersion, normalizedReason, operationId, "start");
                    if (!readParticipants(connection, runId).equals(participants)) {
                        throw new MapAuthorityException(
                                "Map start operation_id was reused with a different participant set: " + operationId
                        );
                    }
                    connection.commit();
                    return;
                }

                MapRunSnapshot current = readRun(connection, runId, true);
                if (current.status() != MapRunStatus.CREATED) {
                    throw new MapAuthorityException("Map run must be CREATED before start: " + runId);
                }
                if (current.stateVersion() != expectedRunStateVersion) {
                    throw new MapAuthorityException("Stale Map run state_version at start: " + runId);
                }
                for (UUID participant : participants) {
                    requirePlayer(connection, participant);
                }

                Instant now = clock.instant();
                for (UUID participant : participants) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO map_run_participants(run_id, player_id, joined_at)
                            VALUES (?, ?, ?)
                            """)) {
                        statement.setObject(1, runId);
                        statement.setObject(2, participant);
                        statement.setTimestamp(3, Timestamp.from(now));
                        statement.executeUpdate();
                    }
                }

                long nextVersion = incrementVersion(current.stateVersion(), "Map run", runId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE map_runs
                        SET status = 'ACTIVE',
                            state_version = ?,
                            started_at = ?,
                            start_operation_id = ?,
                            start_expected_state_version = ?,
                            start_reason = ?
                        WHERE run_id = ? AND status = 'CREATED' AND state_version = ?
                        """)) {
                    statement.setLong(1, nextVersion);
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setObject(3, operationId);
                    statement.setLong(4, expectedRunStateVersion);
                    statement.setString(5, normalizedReason);
                    statement.setObject(6, runId);
                    statement.setLong(7, expectedRunStateVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new MapAuthorityException("Map run changed concurrently during start: " + runId);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Completes one ACTIVE run and appends exactly one immutable clear record. */
    public MapClearSnapshot completeRun(
            UUID operationId,
            UUID runId,
            long expectedRunStateVersion,
            long elapsedMillis,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(runId, "runId");
        if (expectedRunStateVersion < 0) {
            throw new IllegalArgumentException("expectedRunStateVersion must be >= 0");
        }
        if (elapsedMillis <= 0) {
            throw new IllegalArgumentException("elapsedMillis must be > 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<RunActionEvidence> processed = findByTerminalOperation(connection, operationId);
                if (processed.isPresent()) {
                    RunActionEvidence previous = processed.orElseThrow();
                    previous.requireSame(runId, expectedRunStateVersion, normalizedReason, operationId, "completion");
                    if (previous.status() != MapRunStatus.COMPLETED) {
                        throw new MapAuthorityException(
                                "Map terminal operation_id belongs to a non-completion transition: " + operationId
                        );
                    }
                    MapClearSnapshot clear = readClearForRun(connection, runId);
                    if (clear.elapsedMillis() != elapsedMillis) {
                        throw new MapAuthorityException(
                                "Map completion operation_id was reused with a different elapsed time: " + operationId
                        );
                    }
                    connection.commit();
                    return clear;
                }

                MapRunSnapshot current = readRun(connection, runId, true);
                if (current.status() != MapRunStatus.ACTIVE) {
                    throw new MapAuthorityException("Only ACTIVE Map runs may complete: " + runId);
                }
                if (current.stateVersion() != expectedRunStateVersion) {
                    throw new MapAuthorityException("Stale Map run state_version at completion: " + runId);
                }
                List<UUID> participants = readParticipants(connection, runId);
                if (participants.isEmpty()) {
                    throw new MapAuthorityException("Map run cannot complete without participants: " + runId);
                }

                Instant now = clock.instant();
                long nextVersion = incrementVersion(current.stateVersion(), "Map run", runId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE map_runs
                        SET status = 'COMPLETED',
                            state_version = ?,
                            finished_at = ?,
                            terminal_operation_id = ?,
                            terminal_expected_state_version = ?,
                            terminal_reason = ?
                        WHERE run_id = ? AND status = 'ACTIVE' AND state_version = ?
                        """)) {
                    statement.setLong(1, nextVersion);
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setObject(3, operationId);
                    statement.setLong(4, expectedRunStateVersion);
                    statement.setString(5, normalizedReason);
                    statement.setObject(6, runId);
                    statement.setLong(7, expectedRunStateVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new MapAuthorityException("Map run changed concurrently during completion: " + runId);
                    }
                }

                UUID clearId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO map_clears(
                            clear_id,
                            run_id,
                            difficulty,
                            elapsed_millis,
                            solo,
                            world_era_id,
                            balance_version,
                            completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, clearId);
                    statement.setObject(2, runId);
                    statement.setInt(3, current.definition().difficulty().value());
                    statement.setLong(4, elapsedMillis);
                    statement.setBoolean(5, participants.size() == 1);
                    statement.setString(6, current.definition().worldEraId());
                    statement.setInt(7, current.definition().balanceVersion());
                    statement.setTimestamp(8, Timestamp.from(now));
                    statement.executeUpdate();
                }

                MapClearSnapshot result = readClearForRun(connection, runId);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Terminal gameplay/runtime failure consumes the opened Map permanently and never creates a clear. */
    public void failRun(
            UUID operationId,
            UUID runId,
            long expectedRunStateVersion,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(runId, "runId");
        if (expectedRunStateVersion < 0) {
            throw new IllegalArgumentException("expectedRunStateVersion must be >= 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<RunActionEvidence> processed = findByTerminalOperation(connection, operationId);
                if (processed.isPresent()) {
                    RunActionEvidence previous = processed.orElseThrow();
                    previous.requireSame(runId, expectedRunStateVersion, normalizedReason, operationId, "failure");
                    if (previous.status() != MapRunStatus.FAILED) {
                        throw new MapAuthorityException(
                                "Map terminal operation_id belongs to a non-failure transition: " + operationId
                        );
                    }
                    connection.commit();
                    return;
                }

                MapRunSnapshot current = readRun(connection, runId, true);
                if (current.status() != MapRunStatus.CREATED && current.status() != MapRunStatus.ACTIVE) {
                    throw new MapAuthorityException("Only non-terminal Map runs may fail: " + runId);
                }
                if (current.stateVersion() != expectedRunStateVersion) {
                    throw new MapAuthorityException("Stale Map run state_version at failure: " + runId);
                }

                Instant now = clock.instant();
                long nextVersion = incrementVersion(current.stateVersion(), "Map run", runId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE map_runs
                        SET status = 'FAILED',
                            state_version = ?,
                            finished_at = ?,
                            terminal_operation_id = ?,
                            terminal_expected_state_version = ?,
                            terminal_reason = ?
                        WHERE run_id = ? AND status IN ('CREATED', 'ACTIVE') AND state_version = ?
                        """)) {
                    statement.setLong(1, nextVersion);
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setObject(3, operationId);
                    statement.setLong(4, expectedRunStateVersion);
                    statement.setString(5, normalizedReason);
                    statement.setObject(6, runId);
                    statement.setLong(7, expectedRunStateVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new MapAuthorityException("Map run changed concurrently during failure: " + runId);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public List<UUID> listParticipants(UUID runId) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = dataSource.getConnection()) {
            readRun(connection, runId, false);
            return readParticipants(connection, runId);
        }
    }

    public List<MapClearSnapshot> listHighestClears(boolean solo, String worldEraId, int limit) throws SQLException {
        if (worldEraId == null || worldEraId.isBlank()) {
            throw new IllegalArgumentException("worldEraId must not be blank");
        }
        String era = worldEraId.trim();
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT clear_id,
                            run_id,
                            difficulty,
                            elapsed_millis,
                            solo,
                            world_era_id,
                            balance_version,
                            completed_at
                     FROM map_clears
                     WHERE solo = ? AND world_era_id = ?
                     ORDER BY difficulty DESC, elapsed_millis ASC, completed_at ASC, clear_id ASC
                     LIMIT ?
                     """)) {
            statement.setBoolean(1, solo);
            statement.setString(2, era);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<MapClearSnapshot> clears = new ArrayList<>();
                while (rows.next()) {
                    clears.add(readClear(rows));
                }
                return List.copyOf(clears);
            }
        }
    }

    private ItemDefinition requireIndividualDefinition(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new MapAuthorityException("Map items must use INDIVIDUAL item identity: " + definition.definitionId());
        }
        return definition;
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static void requireWorldEra(Connection connection, String worldEraId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM world_eras WHERE era_id = ?")) {
            statement.setString(1, worldEraId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown world era for Map profile: " + worldEraId);
                }
            }
        }
    }

    private static void insertItemInstance(
            Connection connection,
            UUID itemInstanceId,
            String definitionId,
            UUID ownerPlayerId,
            UUID operationId,
            String reason,
            Instant now
    ) throws SQLException {
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
                ) VALUES (?, ?, 'PLAYER_INVENTORY', ?, 0, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setString(2, definitionId);
            statement.setObject(3, ownerPlayerId);
            statement.setObject(4, ownerPlayerId);
            statement.setObject(5, operationId);
            statement.setString(6, reason);
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static void insertMapProfile(
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

    private static Optional<IssuedMapRow> findIssuedMap(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.item_instance_id,
                       i.definition_id,
                       i.original_owner_player_id,
                       i.created_reason,
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
                LEFT JOIN map_item_profiles p ON p.item_instance_id = i.item_instance_id
                WHERE i.created_by_operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                if (row.getObject("difficulty") == null) {
                    throw new MapAuthorityException(
                            "operation_id already created a non-Map item: " + operationId
                    );
                }
                MapItemProfile profile = readMapProfile(row);
                return Optional.of(new IssuedMapRow(
                        profile,
                        row.getObject("original_owner_player_id", UUID.class),
                        row.getString("created_reason")
                ));
            }
        }
    }

    private static MapItemProfile loadMapProfile(Connection connection, UUID itemInstanceId) throws SQLException {
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
                    throw new MapAuthorityException("Unknown Map item profile: " + itemInstanceId);
                }
                return readMapProfile(row);
            }
        }
    }

    private static MapItemProfile readMapProfile(ResultSet row) throws SQLException {
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

    private static MapItemAuthorityRow lockMapItem(Connection connection, UUID itemInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.item_instance_id,
                       i.definition_id,
                       i.location_kind,
                       i.location_id,
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
                FROM item_instances i
                JOIN map_item_profiles p ON p.item_instance_id = i.item_instance_id
                WHERE i.item_instance_id = ?
                FOR UPDATE OF i
                """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown Map item: " + itemInstanceId);
                }
                return new MapItemAuthorityRow(
                        readMapProfile(row),
                        row.getString("location_kind"),
                        row.getObject("location_id", UUID.class),
                        row.getLong("state_version")
                );
            }
        }
    }

    private static void consumeMapItem(
            Connection connection,
            UUID itemInstanceId,
            long expectedVersion,
            long nextVersion,
            Instant now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = 'DESTROYED',
                    location_id = NULL,
                    state_version = ?,
                    updated_at = ?
                WHERE item_instance_id = ?
                  AND state_version = ?
                  AND location_kind = 'PLAYER_INVENTORY'
                """)) {
            statement.setLong(1, nextVersion);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setObject(3, itemInstanceId);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new MapAuthorityException("Map item changed concurrently while opening: " + itemInstanceId);
            }
        }
    }

    private static void insertItemProvenance(
            Connection connection,
            UUID itemInstanceId,
            long sequenceNo,
            UUID operationId,
            String eventType,
            String toLocationKind,
            UUID toLocationId,
            String reason,
            UUID actorPlayerId,
            String fromLocationKind,
            UUID fromLocationId,
            Instant now
    ) throws SQLException {
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
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, sequenceNo);
            statement.setObject(3, operationId);
            statement.setString(4, eventType);
            statement.setString(5, fromLocationKind);
            statement.setObject(6, fromLocationId);
            statement.setString(7, toLocationKind);
            statement.setObject(8, toLocationId);
            statement.setString(9, reason);
            statement.setObject(10, actorPlayerId);
            statement.setTimestamp(11, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static void insertItemLedger(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            UUID itemInstanceId,
            String direction,
            String reason
    ) throws SQLException {
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
                ) VALUES (?, ?, ?, 'ITEM_INSTANCE', ?, 1, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            statement.setObject(3, playerId);
            statement.setString(4, itemInstanceId.toString());
            statement.setString(5, direction);
            statement.setString(6, reason);
            statement.executeUpdate();
        }
    }

    private static void insertRun(
            Connection connection,
            UUID runId,
            UUID sourceItemId,
            MapRunDefinition definition,
            UUID openOperationId,
            UUID openedByPlayerId,
            long expectedItemStateVersion,
            String reason,
            Instant now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO map_runs(
                    run_id,
                    source_map_item_id,
                    status,
                    difficulty,
                    environment_id,
                    enemy_family_id,
                    objective_id,
                    modifier_ids,
                    generation_seed,
                    generation_version,
                    balance_version,
                    world_era_id,
                    state_version,
                    created_at,
                    open_operation_id,
                    opened_by_player_id,
                    source_item_expected_state_version,
                    open_reason
                ) VALUES (?, ?, 'CREATED', ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, runId);
            statement.setObject(2, sourceItemId);
            statement.setInt(3, definition.difficulty().value());
            statement.setString(4, definition.environmentId());
            statement.setString(5, definition.enemyFamilyId());
            statement.setString(6, definition.objectiveId());
            statement.setString(7, writeStringList(definition.modifierIds()));
            statement.setLong(8, definition.generationSeed());
            statement.setInt(9, definition.generationVersion());
            statement.setInt(10, definition.balanceVersion());
            statement.setString(11, definition.worldEraId());
            statement.setTimestamp(12, Timestamp.from(now));
            statement.setObject(13, openOperationId);
            statement.setObject(14, openedByPlayerId);
            statement.setLong(15, expectedItemStateVersion);
            statement.setString(16, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<OpenedRunEvidence> findRunByOpenOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT run_id,
                       source_map_item_id,
                       opened_by_player_id,
                       source_item_expected_state_version,
                       open_reason
                FROM map_runs
                WHERE open_operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OpenedRunEvidence(
                        row.getObject("run_id", UUID.class),
                        row.getObject("source_map_item_id", UUID.class),
                        row.getObject("opened_by_player_id", UUID.class),
                        row.getLong("source_item_expected_state_version"),
                        row.getString("open_reason")
                ));
            }
        }
    }

    private static MapRunSnapshot readRun(Connection connection, UUID runId, boolean forUpdate) throws SQLException {
        String sql = """
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
                       created_at,
                       started_at,
                       finished_at
                FROM map_runs
                WHERE run_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown Map run: " + runId);
                }
                Timestamp startedAt = row.getTimestamp("started_at");
                Timestamp finishedAt = row.getTimestamp("finished_at");
                return new MapRunSnapshot(
                        runId,
                        row.getObject("source_map_item_id", UUID.class),
                        MapRunStatus.valueOf(row.getString("status")),
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
                        startedAt == null ? null : startedAt.toInstant(),
                        finishedAt == null ? null : finishedAt.toInstant()
                );
            }
        }
    }

    private static Optional<RunActionEvidence> findByStartOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT run_id, status, start_expected_state_version, start_reason
                FROM map_runs
                WHERE start_operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RunActionEvidence(
                        row.getObject("run_id", UUID.class),
                        MapRunStatus.valueOf(row.getString("status")),
                        row.getLong("start_expected_state_version"),
                        row.getString("start_reason")
                ));
            }
        }
    }

    private static Optional<RunActionEvidence> findByTerminalOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT run_id, status, terminal_expected_state_version, terminal_reason
                FROM map_runs
                WHERE terminal_operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RunActionEvidence(
                        row.getObject("run_id", UUID.class),
                        MapRunStatus.valueOf(row.getString("status")),
                        row.getLong("terminal_expected_state_version"),
                        row.getString("terminal_reason")
                ));
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
                List<UUID> participants = new ArrayList<>();
                while (rows.next()) {
                    participants.add(rows.getObject("player_id", UUID.class));
                }
                participants.sort(Comparator.comparing(UUID::toString));
                return List.copyOf(participants);
            }
        }
    }

    private static MapClearSnapshot readClearForRun(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT clear_id,
                       run_id,
                       difficulty,
                       elapsed_millis,
                       solo,
                       world_era_id,
                       balance_version,
                       completed_at
                FROM map_clears
                WHERE run_id = ?
                """)) {
            statement.setObject(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Completed Map run has no clear evidence: " + runId);
                }
                return readClear(row);
            }
        }
    }

    private static MapClearSnapshot readClear(ResultSet row) throws SQLException {
        return new MapClearSnapshot(
                row.getObject("clear_id", UUID.class),
                row.getObject("run_id", UUID.class),
                new MapDifficulty(row.getInt("difficulty")),
                row.getLong("elapsed_millis"),
                row.getBoolean("solo"),
                row.getString("world_era_id"),
                row.getInt("balance_version"),
                row.getTimestamp("completed_at").toInstant()
        );
    }

    private static List<UUID> normalizeParticipants(List<UUID> participantPlayerIds) {
        Objects.requireNonNull(participantPlayerIds, "participantPlayerIds");
        if (participantPlayerIds.isEmpty()) {
            throw new IllegalArgumentException("Map run requires at least one participant");
        }
        if (participantPlayerIds.size() > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("Map run participant count exceeds safety ceiling " + MAX_PARTICIPANTS);
        }
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID playerId : participantPlayerIds) {
            if (!unique.add(Objects.requireNonNull(playerId, "participantPlayerIds must not contain null"))) {
                throw new IllegalArgumentException("duplicate Map participant: " + playerId);
            }
        }
        List<UUID> normalized = new ArrayList<>(unique);
        normalized.sort(Comparator.comparing(UUID::toString));
        return List.copyOf(normalized);
    }

    private static String writeStringList(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new MapAuthorityException("Could not serialize Map modifier IDs", exception);
        }
    }

    private static List<String> readStringList(String json) {
        try {
            return List.copyOf(JSON.readValue(json, new TypeReference<List<String>>() { }));
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new MapAuthorityException("Could not parse Map modifier IDs", exception);
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

    private static long incrementVersion(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new MapAuthorityException(target + " state_version overflow for " + id, exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record IssuedMapRow(MapItemProfile profile, UUID originalOwnerPlayerId, String createdReason) {
        private void requireSameRequest(
                String definitionId,
                UUID ownerPlayerId,
                MapRunDefinition runDefinition,
                String reason,
                UUID operationId
        ) {
            if (!profile.definitionId().equals(definitionId)
                    || !originalOwnerPlayerId.equals(ownerPlayerId)
                    || !profile.runDefinition().equals(runDefinition)
                    || !createdReason.equals(reason)) {
                throw new MapAuthorityException(
                        "operation_id reused with a different Map issuance request: " + operationId
                );
            }
        }
    }

    private record MapItemAuthorityRow(
            MapItemProfile profile,
            String locationKind,
            UUID locationId,
            long stateVersion
    ) {
    }

    private record OpenedRunEvidence(
            UUID runId,
            UUID sourceMapItemId,
            UUID openedByPlayerId,
            long expectedItemStateVersion,
            String reason
    ) {
        private void requireSameRequest(
                UUID itemId,
                UUID playerId,
                long expectedVersion,
                String expectedReason,
                UUID operationId
        ) {
            if (!sourceMapItemId.equals(itemId)
                    || !openedByPlayerId.equals(playerId)
                    || expectedItemStateVersion != expectedVersion
                    || !reason.equals(expectedReason)) {
                throw new MapAuthorityException(
                        "operation_id reused with a different Map open request: " + operationId
                );
            }
        }
    }

    private record RunActionEvidence(
            UUID runId,
            MapRunStatus status,
            long expectedStateVersion,
            String reason
    ) {
        private void requireSame(
                UUID expectedRunId,
                long requestedExpectedVersion,
                String requestedReason,
                UUID operationId,
                String action
        ) {
            if (!runId.equals(expectedRunId)
                    || expectedStateVersion != requestedExpectedVersion
                    || !reason.equals(requestedReason)) {
                throw new MapAuthorityException(
                        "operation_id reused with a different Map " + action + " request: " + operationId
                );
            }
        }
    }
}
