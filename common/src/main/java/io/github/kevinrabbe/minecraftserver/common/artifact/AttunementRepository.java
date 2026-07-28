package io.github.kevinrabbe.minecraftserver.common.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One-active-profile authority backed by the shared immutable Artifact discovery point pool. */
public final class AttunementRepository {
    private static final String SET_PROFILE_OPERATION = "ATTUNEMENT_SET_PROFILE";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final AttunementProfileCatalog profiles;

    public AttunementRepository(DataSource dataSource, AttunementProfileCatalog profiles) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    /** Lazily creates the neutral state row; this does not choose a profile or alter discovery evidence. */
    public AttunementSnapshot loadOrInitialize(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requirePlayer(connection, playerId, false);
                ensureState(connection, playerId);
                AttunementSnapshot result = readSnapshot(connection, playerId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public AttunementSnapshot setActiveProfile(UUID operationId, UUID playerId, String profileId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        AttunementProfileDefinition profile = profiles.require(profileId);
        Map<String, Object> request = requestMap(
                "player_id", playerId,
                "profile_id", profile.profileId()
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    AttunementSnapshot replay = snapshotFrom(requireReplay(
                            processed.orElseThrow(), request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                requirePlayer(connection, playerId, true);
                ensureState(connection, playerId);
                AttunementStateRow current = lockState(connection, playerId);
                AttunementSnapshot result;
                if (profile.profileId().equals(current.activeProfileId())) {
                    result = snapshot(connection, current);
                } else {
                    long nextVersion = increment(current.stateVersion(), playerId);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE player_attunement_state
                            SET active_profile_id = ?,
                                state_version = ?,
                                updated_at = NOW()
                            WHERE player_id = ? AND state_version = ?
                            RETURNING updated_at
                            """)) {
                        statement.setString(1, profile.profileId());
                        statement.setLong(2, nextVersion);
                        statement.setObject(3, playerId);
                        statement.setLong(4, current.stateVersion());
                        try (ResultSet row = statement.executeQuery()) {
                            if (!row.next()) {
                                throw new AttunementException("attunement state changed concurrently: " + playerId);
                            }
                            result = new AttunementSnapshot(
                                    playerId,
                                    profile.profileId(),
                                    ArtifactRepository.totalPoints(connection, playerId),
                                    nextVersion,
                                    row.getTimestamp("updated_at").toInstant()
                            );
                        }
                    }
                }

                insertProcessed(connection, operationId, request, snapshotMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static AttunementSnapshot readSnapshot(Connection connection, UUID playerId, boolean forUpdate)
            throws SQLException {
        AttunementStateRow state = readState(connection, playerId, forUpdate)
                .orElseThrow(() -> new AttunementException("attunement state was not initialized: " + playerId));
        return snapshot(connection, state);
    }

    private static AttunementSnapshot snapshot(Connection connection, AttunementStateRow state) throws SQLException {
        return new AttunementSnapshot(
                state.playerId(),
                state.activeProfileId(),
                ArtifactRepository.totalPoints(connection, state.playerId()),
                state.stateVersion(),
                state.updatedAt()
        );
    }

    private static void ensureState(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_attunement_state(player_id, active_profile_id, state_version)
                VALUES (?, NULL, 0)
                ON CONFLICT (player_id) DO NOTHING
                """)) {
            statement.setObject(1, playerId);
            statement.executeUpdate();
        }
    }

    private static AttunementStateRow lockState(Connection connection, UUID playerId) throws SQLException {
        return readState(connection, playerId, true)
                .orElseThrow(() -> new AttunementException("attunement state was not initialized: " + playerId));
    }

    private static Optional<AttunementStateRow> readState(Connection connection, UUID playerId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT player_id, active_profile_id, state_version, updated_at
                FROM player_attunement_state
                WHERE player_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new AttunementStateRow(
                        row.getObject("player_id", UUID.class),
                        row.getString("active_profile_id"),
                        row.getLong("state_version"),
                        row.getTimestamp("updated_at").toInstant()
                ));
            }
        }
    }

    private static void requirePlayer(Connection connection, UUID playerId, boolean forUpdate) throws SQLException {
        String sql = "SELECT 1 FROM players WHERE player_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new AttunementException("Unknown player: " + playerId);
            }
        }
    }

    private static Optional<ProcessedOperation> findProcessed(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type, result::text AS result_json
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new ProcessedOperation(
                        row.getString("operation_type"), readJsonMap(row.getString("result_json"))
                ));
            }
        }
    }

    private static Object requireReplay(
            ProcessedOperation processed,
            Map<String, Object> request,
            UUID operationId
    ) {
        if (!SET_PROFILE_OPERATION.equals(processed.operationType())) {
            throw new AttunementException(
                    "operation_id " + operationId + " already belongs to " + processed.operationType()
            );
        }
        if (!objectMap(processed.data().get("request"), "request").equals(request)) {
            throw new AttunementException("operation_id reused with a different attunement request: " + operationId);
        }
        Object result = processed.data().get("result");
        if (result == null) throw new AttunementException("processed attunement operation is missing result");
        return result;
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            Map<String, Object> request,
            Map<String, Object> result
    ) throws SQLException {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("request", request);
        body.put("result", result);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, SET_PROFILE_OPERATION);
            statement.setString(3, writeJson(body));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requestMap(Object... fields) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            Object value = fields[index + 1];
            result.put(Objects.toString(fields[index]), value == null ? null : Objects.toString(value));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> snapshotMap(AttunementSnapshot snapshot) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("player_id", snapshot.playerId().toString());
        value.put("active_profile_id", snapshot.activeProfileId());
        value.put("total_points", snapshot.totalPoints());
        value.put("state_version", snapshot.stateVersion());
        value.put("updated_at", snapshot.updatedAt().toString());
        return value;
    }

    private static AttunementSnapshot snapshotFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "attunement");
        return new AttunementSnapshot(
                UUID.fromString(stringValue(value, "player_id")),
                nullableString(value.get("active_profile_id")),
                longValue(value, "total_points"),
                longValue(value, "state_version"),
                Instant.parse(stringValue(value, "updated_at"))
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new AttunementException("Could not parse attunement idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AttunementException("Could not serialize attunement idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) throw new AttunementException("attunement field is not an object: " + field);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) throw new AttunementException("missing attunement field: " + field);
        return Objects.toString(raw);
    }

    private static String nullableString(Object value) {
        return value == null ? null : Objects.toString(value);
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(Objects.toString(raw));
        } catch (RuntimeException exception) {
            throw new AttunementException("invalid numeric attunement field: " + field, exception);
        }
    }

    private static long increment(long current, UUID playerId) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new AttunementException("attunement state_version overflow: " + playerId, exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record AttunementStateRow(
            UUID playerId,
            String activeProfileId,
            long stateVersion,
            Instant updatedAt
    ) { }

    private record ProcessedOperation(String operationType, Map<String, Object> data) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            data = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
        }
    }
}
