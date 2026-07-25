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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable authority for hidden world Artifacts.
 *
 * <p>Artifact identity is stable while locations are append-only revisions. Player discovery is immutable and awards
 * its point value once. Attunement Points are derived from discovery evidence rather than maintained as a mutable
 * balance.</p>
 */
public final class ArtifactRepository {
    private static final String CREATE_OPERATION = "ARTIFACT_CREATE";
    private static final String RELOCATE_OPERATION = "ARTIFACT_RELOCATE";
    private static final String DISCOVER_OPERATION = "ARTIFACT_DISCOVER";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public ArtifactRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public ArtifactDefinitionSnapshot createArtifact(
            UUID operationId,
            UUID artifactId,
            int pointValue,
            int pointPolicyVersion,
            boolean enabled,
            String worldKey,
            String logicalZoneId,
            int blockX,
            int blockY,
            int blockZ
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(artifactId, "artifactId");
        String world = requireNonBlank(worldKey, "worldKey");
        String zone = normalizeOptional(logicalZoneId);
        if (pointValue <= 0 || pointValue > 1_000 || pointPolicyVersion < 1) {
            throw new IllegalArgumentException("invalid artifact point policy");
        }
        Map<String, Object> request = requestMap(
                "artifact_id", artifactId,
                "point_value", pointValue,
                "point_policy_version", pointPolicyVersion,
                "enabled", enabled,
                "world_key", world,
                "logical_zone_id", zone,
                "block_x", blockX,
                "block_y", blockY,
                "block_z", blockZ
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ArtifactDefinitionSnapshot replay = definitionFrom(requireReplay(
                            processed.orElseThrow(), CREATE_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO artifact_definitions(
                            artifact_id,
                            definition_operation_id,
                            point_value,
                            point_policy_version,
                            enabled
                        ) VALUES (?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, artifactId);
                    statement.setObject(2, operationId);
                    statement.setInt(3, pointValue);
                    statement.setInt(4, pointPolicyVersion);
                    statement.setBoolean(5, enabled);
                    statement.executeUpdate();
                }
                insertLocation(connection, artifactId, 1L, operationId, world, zone, blockX, blockY, blockZ);

                ArtifactDefinitionSnapshot result = requireDefinition(connection, artifactId, false);
                insertProcessed(connection, operationId, CREATE_OPERATION, request, definitionMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ArtifactDefinitionSnapshot relocate(
            UUID operationId,
            UUID artifactId,
            long expectedLocationRevision,
            String worldKey,
            String logicalZoneId,
            int blockX,
            int blockY,
            int blockZ
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(artifactId, "artifactId");
        if (expectedLocationRevision < 1) {
            throw new IllegalArgumentException("expectedLocationRevision must be >= 1");
        }
        String world = requireNonBlank(worldKey, "worldKey");
        String zone = normalizeOptional(logicalZoneId);
        Map<String, Object> request = requestMap(
                "artifact_id", artifactId,
                "expected_location_revision", expectedLocationRevision,
                "world_key", world,
                "logical_zone_id", zone,
                "block_x", blockX,
                "block_y", blockY,
                "block_z", blockZ
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ArtifactDefinitionSnapshot replay = definitionFrom(requireReplay(
                            processed.orElseThrow(), RELOCATE_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                lockDefinition(connection, artifactId, false);
                ArtifactLocationSnapshot current = requireCurrentLocation(connection, artifactId);
                if (current.locationRevision() != expectedLocationRevision) {
                    throw new ArtifactException(
                            "stale artifact location revision for " + artifactId
                                    + ": expected " + expectedLocationRevision
                                    + " but current is " + current.locationRevision()
                    );
                }
                long nextRevision = increment(current.locationRevision(), "artifact location", artifactId);
                insertLocation(
                        connection, artifactId, nextRevision, operationId, world, zone, blockX, blockY, blockZ
                );

                ArtifactDefinitionSnapshot result = requireDefinition(connection, artifactId, false);
                insertProcessed(connection, operationId, RELOCATE_OPERATION, request, definitionMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ArtifactDiscoveryResult discover(
            UUID operationId,
            UUID playerId,
            UUID artifactId,
            long expectedLocationRevision,
            String worldEraContext
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(artifactId, "artifactId");
        if (expectedLocationRevision < 1) {
            throw new IllegalArgumentException("expectedLocationRevision must be >= 1");
        }
        String era = normalizeEra(worldEraContext);
        Map<String, Object> request = requestMap(
                "player_id", playerId,
                "artifact_id", artifactId,
                "expected_location_revision", expectedLocationRevision,
                "world_era_context", era
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ArtifactDiscoveryResult replay = discoveryResultFrom(requireReplay(
                            processed.orElseThrow(), DISCOVER_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                lockPlayer(connection, playerId);
                DefinitionRow definition = lockDefinition(connection, artifactId, true);
                if (!definition.enabled()) {
                    throw new ArtifactException("artifact is disabled: " + artifactId);
                }
                ArtifactLocationSnapshot currentLocation = requireCurrentLocation(connection, artifactId);
                if (currentLocation.locationRevision() != expectedLocationRevision) {
                    throw new ArtifactException(
                            "stale artifact interaction revision for " + artifactId
                                    + ": expected " + expectedLocationRevision
                                    + " but current is " + currentLocation.locationRevision()
                    );
                }

                Optional<ArtifactDiscoverySnapshot> existing = readDiscovery(connection, playerId, artifactId);
                if (existing.isPresent()) {
                    long totalPoints = totalPoints(connection, playerId);
                    ArtifactDiscoveryResult result = new ArtifactDiscoveryResult(
                            existing.orElseThrow(), false, totalPoints
                    );
                    insertProcessed(connection, operationId, DISCOVER_OPERATION, request, discoveryResultMap(result));
                    connection.commit();
                    return result;
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO player_artifact_discoveries(
                            player_id,
                            artifact_id,
                            operation_id,
                            location_revision,
                            points_awarded,
                            point_policy_version,
                            world_era_context
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        RETURNING discovered_at
                        """)) {
                    statement.setObject(1, playerId);
                    statement.setObject(2, artifactId);
                    statement.setObject(3, operationId);
                    statement.setLong(4, currentLocation.locationRevision());
                    statement.setInt(5, definition.pointValue());
                    statement.setInt(6, definition.pointPolicyVersion());
                    if (era == null) statement.setNull(7, java.sql.Types.VARCHAR);
                    else statement.setString(7, era);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new ArtifactException("artifact discovery insert returned no row");
                        ArtifactDiscoverySnapshot discovery = new ArtifactDiscoverySnapshot(
                                playerId,
                                artifactId,
                                currentLocation.locationRevision(),
                                definition.pointValue(),
                                definition.pointPolicyVersion(),
                                era,
                                row.getTimestamp("discovered_at").toInstant()
                        );
                        long totalPoints = totalPoints(connection, playerId);
                        ArtifactDiscoveryResult result = new ArtifactDiscoveryResult(discovery, true, totalPoints);
                        insertProcessed(
                                connection, operationId, DISCOVER_OPERATION, request, discoveryResultMap(result)
                        );
                        connection.commit();
                        return result;
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public Optional<ArtifactDefinitionSnapshot> loadDefinition(UUID artifactId) throws SQLException {
        Objects.requireNonNull(artifactId, "artifactId");
        try (Connection connection = dataSource.getConnection()) {
            return readDefinition(connection, artifactId, false);
        }
    }

    public List<ArtifactDiscoverySnapshot> listDiscoveries(UUID playerId, int limit) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        if (limit <= 0 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id,
                            artifact_id,
                            location_revision,
                            points_awarded,
                            point_policy_version,
                            world_era_context,
                            discovered_at
                     FROM player_artifact_discoveries
                     WHERE player_id = ?
                     ORDER BY discovered_at, artifact_id
                     LIMIT ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<ArtifactDiscoverySnapshot> result = new ArrayList<>();
                while (rows.next()) result.add(discoverySnapshot(rows));
                return List.copyOf(result);
            }
        }
    }

    public long totalAttunementPoints(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            requirePlayer(connection, playerId, false);
            return totalPoints(connection, playerId);
        }
    }

    private static Optional<ArtifactDefinitionSnapshot> readDefinition(
            Connection connection,
            UUID artifactId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT artifact_id, point_value, point_policy_version, enabled, created_at, updated_at
                FROM artifact_definitions
                WHERE artifact_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, artifactId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                ArtifactLocationSnapshot location = requireCurrentLocation(connection, artifactId);
                return Optional.of(new ArtifactDefinitionSnapshot(
                        artifactId,
                        row.getInt("point_value"),
                        row.getInt("point_policy_version"),
                        row.getBoolean("enabled"),
                        location,
                        row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant()
                ));
            }
        }
    }

    private static ArtifactDefinitionSnapshot requireDefinition(
            Connection connection,
            UUID artifactId,
            boolean forUpdate
    ) throws SQLException {
        return readDefinition(connection, artifactId, forUpdate)
                .orElseThrow(() -> new ArtifactException("Unknown artifact: " + artifactId));
    }

    /**
     * Locks the definition. Discovery uses FOR SHARE so different players can discover concurrently while relocation,
     * which uses FOR UPDATE, cannot race the location-revision check.
     */
    private static DefinitionRow lockDefinition(Connection connection, UUID artifactId, boolean shared)
            throws SQLException {
        String lock = shared ? " FOR SHARE" : " FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT point_value, point_policy_version, enabled
                FROM artifact_definitions
                WHERE artifact_id = ?
                """ + lock)) {
            statement.setObject(1, artifactId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ArtifactException("Unknown artifact: " + artifactId);
                return new DefinitionRow(
                        row.getInt("point_value"),
                        row.getInt("point_policy_version"),
                        row.getBoolean("enabled")
                );
            }
        }
    }

    private static ArtifactLocationSnapshot requireCurrentLocation(Connection connection, UUID artifactId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT artifact_id,
                       location_revision,
                       world_key,
                       logical_zone_id,
                       block_x,
                       block_y,
                       block_z,
                       created_at
                FROM artifact_locations
                WHERE artifact_id = ?
                ORDER BY location_revision DESC
                LIMIT 1
                """)) {
            statement.setObject(1, artifactId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ArtifactException("artifact has no location: " + artifactId);
                return locationSnapshot(row);
            }
        }
    }

    private static Optional<ArtifactDiscoverySnapshot> readDiscovery(
            Connection connection,
            UUID playerId,
            UUID artifactId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id,
                       artifact_id,
                       location_revision,
                       points_awarded,
                       point_policy_version,
                       world_era_context,
                       discovered_at
                FROM player_artifact_discoveries
                WHERE player_id = ? AND artifact_id = ?
                """)) {
            statement.setObject(1, playerId);
            statement.setObject(2, artifactId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(discoverySnapshot(row)) : Optional.empty();
            }
        }
    }

    private static void insertLocation(
            Connection connection,
            UUID artifactId,
            long revision,
            UUID operationId,
            String worldKey,
            String logicalZoneId,
            int blockX,
            int blockY,
            int blockZ
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO artifact_locations(
                    artifact_id,
                    location_revision,
                    operation_id,
                    world_key,
                    logical_zone_id,
                    block_x,
                    block_y,
                    block_z
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, artifactId);
            statement.setLong(2, revision);
            statement.setObject(3, operationId);
            statement.setString(4, worldKey);
            if (logicalZoneId == null) statement.setNull(5, java.sql.Types.VARCHAR);
            else statement.setString(5, logicalZoneId);
            statement.setInt(6, blockX);
            statement.setInt(7, blockY);
            statement.setInt(8, blockZ);
            statement.executeUpdate();
        }
    }

    private static void lockPlayer(Connection connection, UUID playerId) throws SQLException {
        requirePlayer(connection, playerId, true);
    }

    private static void requirePlayer(Connection connection, UUID playerId, boolean forUpdate) throws SQLException {
        String sql = "SELECT 1 FROM players WHERE player_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ArtifactException("Unknown player: " + playerId);
            }
        }
    }

    static long totalPoints(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(points_awarded), 0) AS total_points
                FROM player_artifact_discoveries
                WHERE player_id = ?
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong("total_points");
            }
        }
    }

    private static ArtifactLocationSnapshot locationSnapshot(ResultSet row) throws SQLException {
        return new ArtifactLocationSnapshot(
                row.getObject("artifact_id", UUID.class),
                row.getLong("location_revision"),
                row.getString("world_key"),
                row.getString("logical_zone_id"),
                row.getInt("block_x"),
                row.getInt("block_y"),
                row.getInt("block_z"),
                row.getTimestamp("created_at").toInstant()
        );
    }

    private static ArtifactDiscoverySnapshot discoverySnapshot(ResultSet row) throws SQLException {
        return new ArtifactDiscoverySnapshot(
                row.getObject("player_id", UUID.class),
                row.getObject("artifact_id", UUID.class),
                row.getLong("location_revision"),
                row.getInt("points_awarded"),
                row.getInt("point_policy_version"),
                row.getString("world_era_context"),
                row.getTimestamp("discovered_at").toInstant()
        );
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
            String expectedType,
            Map<String, Object> request,
            UUID operationId
    ) {
        if (!expectedType.equals(processed.operationType())) {
            throw new ArtifactException(
                    "operation_id " + operationId + " already belongs to " + processed.operationType()
            );
        }
        if (!objectMap(processed.data().get("request"), "request").equals(request)) {
            throw new ArtifactException("operation_id reused with a different artifact request: " + operationId);
        }
        Object result = processed.data().get("result");
        if (result == null) throw new ArtifactException("processed artifact operation is missing result");
        return result;
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            String operationType,
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
            statement.setString(2, operationType);
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

    private static Map<String, Object> locationMap(ArtifactLocationSnapshot location) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("artifact_id", location.artifactId().toString());
        value.put("location_revision", location.locationRevision());
        value.put("world_key", location.worldKey());
        value.put("logical_zone_id", location.logicalZoneId());
        value.put("block_x", location.blockX());
        value.put("block_y", location.blockY());
        value.put("block_z", location.blockZ());
        value.put("created_at", location.createdAt().toString());
        return value;
    }

    private static ArtifactLocationSnapshot locationFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "location");
        return new ArtifactLocationSnapshot(
                uuidValue(value, "artifact_id"),
                longValue(value, "location_revision"),
                stringValue(value, "world_key"),
                nullableString(value.get("logical_zone_id")),
                intValue(value, "block_x"),
                intValue(value, "block_y"),
                intValue(value, "block_z"),
                Instant.parse(stringValue(value, "created_at"))
        );
    }

    private static Map<String, Object> definitionMap(ArtifactDefinitionSnapshot definition) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("artifact_id", definition.artifactId().toString());
        value.put("point_value", definition.pointValue());
        value.put("point_policy_version", definition.pointPolicyVersion());
        value.put("enabled", definition.enabled());
        value.put("current_location", locationMap(definition.currentLocation()));
        value.put("created_at", definition.createdAt().toString());
        value.put("updated_at", definition.updatedAt().toString());
        return value;
    }

    private static ArtifactDefinitionSnapshot definitionFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "definition");
        return new ArtifactDefinitionSnapshot(
                uuidValue(value, "artifact_id"),
                intValue(value, "point_value"),
                intValue(value, "point_policy_version"),
                booleanValue(value, "enabled"),
                locationFrom(value.get("current_location")),
                Instant.parse(stringValue(value, "created_at")),
                Instant.parse(stringValue(value, "updated_at"))
        );
    }

    private static Map<String, Object> discoveryMap(ArtifactDiscoverySnapshot discovery) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("player_id", discovery.playerId().toString());
        value.put("artifact_id", discovery.artifactId().toString());
        value.put("location_revision", discovery.locationRevision());
        value.put("points_awarded", discovery.pointsAwarded());
        value.put("point_policy_version", discovery.pointPolicyVersion());
        value.put("world_era_context", discovery.worldEraContext());
        value.put("discovered_at", discovery.discoveredAt().toString());
        return value;
    }

    private static ArtifactDiscoverySnapshot discoveryFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "discovery");
        return new ArtifactDiscoverySnapshot(
                uuidValue(value, "player_id"),
                uuidValue(value, "artifact_id"),
                longValue(value, "location_revision"),
                intValue(value, "points_awarded"),
                intValue(value, "point_policy_version"),
                nullableString(value.get("world_era_context")),
                Instant.parse(stringValue(value, "discovered_at"))
        );
    }

    private static Map<String, Object> discoveryResultMap(ArtifactDiscoveryResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("discovery", discoveryMap(result.discovery()));
        value.put("newly_discovered", result.newlyDiscovered());
        value.put("total_attunement_points", result.totalAttunementPoints());
        return value;
    }

    private static ArtifactDiscoveryResult discoveryResultFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "discovery_result");
        return new ArtifactDiscoveryResult(
                discoveryFrom(value.get("discovery")),
                booleanValue(value, "newly_discovered"),
                longValue(value, "total_attunement_points")
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ArtifactException("Could not parse artifact idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ArtifactException("Could not serialize artifact idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) throw new ArtifactException("artifact field is not an object: " + field);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) throw new ArtifactException("missing artifact field: " + field);
        return Objects.toString(raw);
    }

    private static String nullableString(Object value) {
        return value == null ? null : Objects.toString(value);
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static int intValue(Map<String, Object> value, String field) {
        return Math.toIntExact(longValue(value, field));
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(Objects.toString(raw));
        } catch (RuntimeException exception) {
            throw new ArtifactException("invalid numeric artifact field: " + field, exception);
        }
    }

    private static boolean booleanValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw instanceof Boolean bool) return bool;
        if (raw != null) return Boolean.parseBoolean(Objects.toString(raw));
        throw new ArtifactException("missing boolean artifact field: " + field);
    }

    private static long increment(long current, String authority, Object id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new ArtifactException(authority + " revision overflow: " + id, exception);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeEra(String value) {
        String normalized = normalizeOptional(value);
        if (normalized != null && normalized.length() > 128) {
            throw new IllegalArgumentException("worldEraContext must be <= 128 characters");
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

    private record DefinitionRow(int pointValue, int pointPolicyVersion, boolean enabled) { }

    private record ProcessedOperation(String operationType, Map<String, Object> data) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            data = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
        }
    }
}
