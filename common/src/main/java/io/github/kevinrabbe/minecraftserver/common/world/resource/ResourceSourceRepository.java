package io.github.kevinrabbe.minecraftserver.common.world.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import io.github.kevinrabbe.minecraftserver.common.session.SessionStatus;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** PostgreSQL authority for registered renewable sources and one-cycle harvest entitlements. */
public final class ResourceSourceRepository {
    private static final String HARVEST_OPERATION = "RESOURCE_SOURCE_HARVEST";
    private static final Pattern SOURCE_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final ResourceSourceCatalog catalog;

    public ResourceSourceRepository(DataSource dataSource, ResourceSourceCatalog catalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** Idempotently registers one template source for one concrete live/resettable instance. */
    public ResourceSourceSnapshot ensureSource(
            UUID instanceId,
            String sourceKey,
            String definitionId
    ) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        String normalizedSourceKey = requireSourceKey(sourceKey);
        ResourceSourceDefinition definition = catalog.require(definitionId);
        UUID sourceId = deterministicSourceId(instanceId, normalizedSourceKey);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ZoneInstance instance = readInstance(connection, instanceId, true);
                requireInstanceMatchesDefinition(instance, definition);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO resource_sources(
                            source_id, instance_id, source_key, definition_id, cycle_no, next_available_at, state_version
                        ) VALUES (?, ?, ?, ?, 0, NOW(), 0)
                        ON CONFLICT (instance_id, source_key) DO NOTHING
                        """)) {
                    statement.setObject(1, sourceId);
                    statement.setObject(2, instanceId);
                    statement.setString(3, normalizedSourceKey);
                    statement.setString(4, definition.definitionId());
                    statement.executeUpdate();
                }
                ResourceSourceSnapshot source = readSourceByInstanceKey(
                        connection, instanceId, normalizedSourceKey, false
                );
                if (!source.sourceId().equals(sourceId)
                        || !source.definitionId().equals(definition.definitionId())) {
                    throw new ResourceSourceException(
                            "source key already belongs to a different source definition: " + normalizedSourceKey
                    );
                }
                connection.commit();
                return source;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ResourceSourceSnapshot loadSource(UUID sourceId) throws SQLException {
        Objects.requireNonNull(sourceId, "sourceId");
        try (Connection connection = dataSource.getConnection()) {
            return readSource(connection, sourceId, false).source();
        }
    }

    /** Consumes exactly one currently available source cycle into an immutable reward entitlement. */
    public ResourceHarvestEntitlement harvest(
            UUID operationId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            UUID sourceId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sourceId, "sourceId");
        if (expectedPlayerStateVersion < 0) {
            throw new IllegalArgumentException("expectedPlayerStateVersion must be >= 0");
        }
        String backend = requireNonBlank(backendId, "backendId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), operationId);
                    requireUuid(data, "session_id", sessionId, operationId);
                    requireString(data, "backend_id", backend, operationId);
                    requireLong(data, "expected_player_state_version", expectedPlayerStateVersion, operationId);
                    requireUuid(data, "source_id", sourceId, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    ResourceHarvestEntitlement entitlement = entitlementFrom(data.get("entitlement"));
                    connection.commit();
                    return entitlement;
                }

                LiveSession session = lockLiveSession(
                        connection, sessionId, backend, expectedPlayerStateVersion
                );
                LockedSource locked = readSource(connection, sourceId, true);
                ResourceSourceSnapshot source = locked.source();
                ResourceSourceDefinition definition = catalog.require(source.definitionId());
                requireInstanceMatchesDefinition(locked.instance(), definition);
                if (!locked.instance().status().equals("ACTIVE")) {
                    throw new ResourceSourceException("resource source instance is not ACTIVE: " + source.instanceId());
                }
                if (!source.instanceId().equals(session.instanceId())) {
                    throw new ResourceSourceException("player session is not attached to the resource source instance");
                }
                if (source.nextAvailableAt().isAfter(locked.databaseNow())) {
                    throw new ResourceSourceException("resource source is still in cooldown: " + source.sourceId());
                }

                long consumedCycle = source.cycleNo();
                long nextCycle = increment(source.cycleNo(), "source cycle", source.sourceId());
                long nextVersion = increment(source.stateVersion(), "source state_version", source.sourceId());
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE resource_sources
                        SET cycle_no = ?,
                            next_available_at = NOW() + (? * INTERVAL '1 millisecond'),
                            state_version = ?,
                            updated_at = NOW()
                        WHERE source_id = ?
                          AND cycle_no = ?
                          AND state_version = ?
                          AND next_available_at <= NOW()
                        """)) {
                    statement.setLong(1, nextCycle);
                    statement.setLong(2, definition.respawnDelay().toMillis());
                    statement.setLong(3, nextVersion);
                    statement.setObject(4, source.sourceId());
                    statement.setLong(5, source.cycleNo());
                    statement.setLong(6, source.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new ResourceSourceException("resource source changed concurrently during harvest");
                    }
                }

                UUID harvestId = UUID.randomUUID();
                ResourceHarvestEntitlement entitlement = insertHarvest(
                        connection,
                        harvestId,
                        operationId,
                        source.sourceId(),
                        consumedCycle,
                        session.playerId(),
                        definition
                );
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("session_id", sessionId.toString());
                data.put("backend_id", backend);
                data.put("expected_player_state_version", expectedPlayerStateVersion);
                data.put("source_id", sourceId.toString());
                data.put("reason", normalizedReason);
                data.put("entitlement", entitlementMap(entitlement));
                insertProcessed(connection, operationId, data);
                connection.commit();
                return entitlement;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static ZoneInstance readInstance(Connection connection, UUID instanceId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT zone_id, template_version, status
                FROM zone_instances
                WHERE instance_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, instanceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ResourceSourceException("Unknown zone instance: " + instanceId);
                }
                return new ZoneInstance(
                        instanceId,
                        row.getString("zone_id"),
                        row.getString("template_version"),
                        row.getString("status")
                );
            }
        }
    }

    private static void requireInstanceMatchesDefinition(
            ZoneInstance instance,
            ResourceSourceDefinition definition
    ) {
        if (!instance.zoneId().equals(definition.zoneId())
                || !instance.templateVersion().equals(definition.templateVersion())) {
            throw new ResourceSourceException(
                    "zone instance does not match resource definition zone/template: " + instance.instanceId()
            );
        }
        if (!instance.status().equals("STARTING") && !instance.status().equals("ACTIVE")) {
            throw new ResourceSourceException(
                    "resource sources may be registered only on STARTING/ACTIVE instances: " + instance.instanceId()
            );
        }
    }

    private static LiveSession lockLiveSession(
            Connection connection,
            UUID sessionId,
            String backendId,
            long expectedStateVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id,
                       owner_backend_id,
                       owner_instance_id,
                       state_version,
                       status,
                       lease_expires_at IS NOT NULL AND lease_expires_at > NOW() AS lease_valid
                FROM player_sessions
                WHERE network_session_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SessionConflictException("Unknown session: " + sessionId);
                }
                UUID playerId = row.getObject("player_id", UUID.class);
                String ownerBackendId = row.getString("owner_backend_id");
                UUID instanceId = row.getObject("owner_instance_id", UUID.class);
                long stateVersion = row.getLong("state_version");
                SessionStatus status = SessionStatus.valueOf(row.getString("status"));
                boolean leaseValid = row.getBoolean("lease_valid");
                if (!backendId.equals(ownerBackendId)
                        || instanceId == null
                        || stateVersion != expectedStateVersion
                        || !leaseValid
                        || (status != SessionStatus.ACTIVE && status != SessionStatus.RECOVERING)) {
                    throw new SessionConflictException(
                            "resource harvest does not match authoritative live instance session"
                    );
                }
                return new LiveSession(playerId, instanceId);
            }
        }
    }

    private static LockedSource readSource(Connection connection, UUID sourceId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT s.instance_id,
                       s.source_key,
                       s.definition_id,
                       s.cycle_no,
                       s.next_available_at,
                       s.state_version,
                       z.zone_id,
                       z.template_version,
                       z.status AS instance_status,
                       NOW() AS database_now
                FROM resource_sources s
                JOIN zone_instances z ON z.instance_id = s.instance_id
                WHERE s.source_id = ?
                """ + (forUpdate ? " FOR UPDATE OF s, z" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sourceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ResourceSourceException("Unknown resource source: " + sourceId);
                }
                UUID instanceId = row.getObject("instance_id", UUID.class);
                ResourceSourceSnapshot source = new ResourceSourceSnapshot(
                        sourceId,
                        instanceId,
                        row.getString("source_key"),
                        row.getString("definition_id"),
                        row.getLong("cycle_no"),
                        row.getTimestamp("next_available_at").toInstant(),
                        row.getLong("state_version")
                );
                ZoneInstance instance = new ZoneInstance(
                        instanceId,
                        row.getString("zone_id"),
                        row.getString("template_version"),
                        row.getString("instance_status")
                );
                return new LockedSource(source, instance, row.getTimestamp("database_now").toInstant());
            }
        }
    }

    private static ResourceSourceSnapshot readSourceByInstanceKey(
            Connection connection,
            UUID instanceId,
            String sourceKey,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT source_id, definition_id, cycle_no, next_available_at, state_version
                FROM resource_sources
                WHERE instance_id = ? AND source_key = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, instanceId);
            statement.setString(2, sourceKey);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ResourceSourceException("resource source registration disappeared concurrently");
                }
                return new ResourceSourceSnapshot(
                        row.getObject("source_id", UUID.class),
                        instanceId,
                        sourceKey,
                        row.getString("definition_id"),
                        row.getLong("cycle_no"),
                        row.getTimestamp("next_available_at").toInstant(),
                        row.getLong("state_version")
                );
            }
        }
    }

    private static ResourceHarvestEntitlement insertHarvest(
            Connection connection,
            UUID harvestId,
            UUID operationId,
            UUID sourceId,
            long sourceCycleNo,
            UUID playerId,
            ResourceSourceDefinition definition
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_harvests(
                    harvest_id,
                    operation_id,
                    source_id,
                    source_cycle_no,
                    player_id,
                    commodity_definition_id,
                    commodity_quantity,
                    skill_id,
                    requested_experience
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING created_at
                """)) {
            statement.setObject(1, harvestId);
            statement.setObject(2, operationId);
            statement.setObject(3, sourceId);
            statement.setLong(4, sourceCycleNo);
            statement.setObject(5, playerId);
            statement.setString(6, definition.commodityDefinitionId());
            statement.setLong(7, definition.commodityQuantity());
            if (definition.skillId() == null) {
                statement.setNull(8, java.sql.Types.VARCHAR);
            } else {
                statement.setString(8, definition.skillId().value());
            }
            statement.setLong(9, definition.requestedExperience());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return new ResourceHarvestEntitlement(
                        harvestId,
                        operationId,
                        sourceId,
                        sourceCycleNo,
                        playerId,
                        definition.commodityDefinitionId(),
                        definition.commodityQuantity(),
                        definition.skillId(),
                        definition.requestedExperience(),
                        row.getTimestamp("created_at").toInstant()
                );
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
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ProcessedOperation(
                        row.getString("operation_type"),
                        readJsonMap(row.getString("result_json"))
                ));
            }
        }
    }

    private static void insertProcessed(Connection connection, UUID operationId, Map<String, Object> data)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, HARVEST_OPERATION);
            statement.setString(3, writeJson(data));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> entitlementMap(ResourceHarvestEntitlement entitlement) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("harvest_id", entitlement.harvestId().toString());
        value.put("operation_id", entitlement.operationId().toString());
        value.put("source_id", entitlement.sourceId().toString());
        value.put("source_cycle_no", entitlement.sourceCycleNo());
        value.put("player_id", entitlement.playerId().toString());
        value.put("commodity_definition_id", entitlement.commodityDefinitionId());
        value.put("commodity_quantity", entitlement.commodityQuantity());
        value.put("skill_id", entitlement.skillId() == null ? null : entitlement.skillId().value());
        value.put("requested_experience", entitlement.requestedExperience());
        value.put("created_at", entitlement.createdAt().toString());
        return value;
    }

    private static ResourceHarvestEntitlement entitlementFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "entitlement");
        String skillId = nullableString(value, "skill_id");
        return new ResourceHarvestEntitlement(
                uuidValue(value, "harvest_id"),
                uuidValue(value, "operation_id"),
                uuidValue(value, "source_id"),
                longValue(value, "source_cycle_no"),
                uuidValue(value, "player_id"),
                stringValue(value, "commodity_definition_id"),
                longValue(value, "commodity_quantity"),
                skillId == null ? null : new io.github.kevinrabbe.minecraftserver.common.progression.SkillId(skillId),
                longValue(value, "requested_experience"),
                Instant.parse(stringValue(value, "created_at"))
        );
    }

    private static Map<String, Object> requireType(ProcessedOperation operation, UUID operationId) {
        if (!HARVEST_OPERATION.equals(operation.operationType())) {
            throw new ResourceSourceException(
                    "operation_id " + operationId + " already belongs to " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ResourceSourceException("Could not parse resource harvest idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResourceSourceException("Could not serialize resource harvest result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ResourceSourceException("resource harvest field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new ResourceSourceException("resource harvest result is missing field: " + field);
        }
        return Objects.toString(raw);
    }

    private static String nullableString(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw == null ? null : Objects.toString(raw);
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new ResourceSourceException("resource harvest field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!uuidValue(data, field).equals(expected)) {
            throw reused(operationId);
        }
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) {
            throw reused(operationId);
        }
    }

    private static void requireLong(Map<String, Object> data, String field, long expected, UUID operationId) {
        if (longValue(data, field) != expected) {
            throw reused(operationId);
        }
    }

    private static ResourceSourceException reused(UUID operationId) {
        return new ResourceSourceException("operation_id reused with a different resource harvest request: " + operationId);
    }

    private static UUID deterministicSourceId(UUID instanceId, String sourceKey) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:resource-source:" + instanceId + ":" + sourceKey)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static long increment(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new ResourceSourceException(target + " overflow for " + id, exception);
        }
    }

    private static String requireSourceKey(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey must not be blank");
        }
        String normalized = sourceKey.trim();
        if (!SOURCE_KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException("sourceKey has invalid format: " + normalized);
        }
        return normalized;
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

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record ZoneInstance(UUID instanceId, String zoneId, String templateVersion, String status) {
    }

    private record LiveSession(UUID playerId, UUID instanceId) {
    }

    private record LockedSource(ResourceSourceSnapshot source, ZoneInstance instance, Instant databaseNow) {
    }

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Map.copyOf(Objects.requireNonNull(result, "result"));
        }
    }
}
