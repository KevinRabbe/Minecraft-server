package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Exactly-once authority binding an ACTIVE leased bounty summon to one disposable Minecraft entity identity. */
public final class BountyBossMaterializationRepository {
    private static final String OPERATION_TYPE = "BOUNTY_BOSS_MATERIALIZE";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final BountyTierCatalog catalog;

    public BountyBossMaterializationRepository(DataSource dataSource, BountyTierCatalog catalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public BountyBossMaterializationSnapshot record(
            UUID operationId,
            UUID summonId,
            String backendId,
            String bossDefinitionId,
            UUID entityUuid,
            String worldName,
            double spawnX,
            double spawnY,
            double spawnZ
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(summonId, "summonId");
        Objects.requireNonNull(entityUuid, "entityUuid");
        String backend = requireText(backendId, "backendId");
        String bossDefinition = requireText(bossDefinitionId, "bossDefinitionId");
        String world = requireText(worldName, "worldName");
        requireFinite(spawnX, spawnY, spawnZ);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), operationId);
                    requireUuid(data, "summon_id", summonId, operationId);
                    requireString(data, "backend_id", backend, operationId);
                    requireString(data, "boss_definition_id", bossDefinition, operationId);
                    requireUuid(data, "entity_uuid", entityUuid, operationId);
                    requireString(data, "world_name", world, operationId);
                    requireDouble(data, "spawn_x", spawnX, operationId);
                    requireDouble(data, "spawn_y", spawnY, operationId);
                    requireDouble(data, "spawn_z", spawnZ, operationId);
                    BountyBossMaterializationSnapshot replay = snapshotFrom(data.get("materialization"));
                    connection.commit();
                    return replay;
                }

                LockedSummon summon = lockActiveSummon(connection, summonId);
                if (!backend.equals(summon.ownerBackendId())) {
                    throw new BountyException("Backend does not own ACTIVE bounty summon: " + summonId);
                }
                BountyTierDefinition tier = catalog.require(summon.familyId(), summon.tier());
                if (!bossDefinition.equals(tier.bossDefinitionId())) {
                    throw new BountyException(
                            "Boss definition does not match bounty tier for summon " + summonId
                    );
                }

                Optional<BountyBossMaterializationSnapshot> existing = readBySummon(connection, summonId);
                if (existing.isPresent()) {
                    BountyBossMaterializationSnapshot value = existing.orElseThrow();
                    requireSameMaterialization(
                            value,
                            backend,
                            bossDefinition,
                            entityUuid,
                            world,
                            spawnX,
                            spawnY,
                            spawnZ,
                            summonId
                    );
                    LinkedHashMap<String, Object> data = requestMap(
                            summonId, backend, bossDefinition, entityUuid, world, spawnX, spawnY, spawnZ
                    );
                    data.put("materialization", snapshotMap(value));
                    insertProcessed(connection, operationId, data);
                    connection.commit();
                    return value;
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO bounty_boss_materializations(
                            summon_id,
                            entity_uuid,
                            backend_id,
                            boss_definition_id,
                            world_name,
                            spawn_x,
                            spawn_y,
                            spawn_z
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, summonId);
                    statement.setObject(2, entityUuid);
                    statement.setString(3, backend);
                    statement.setString(4, bossDefinition);
                    statement.setString(5, world);
                    statement.setDouble(6, spawnX);
                    statement.setDouble(7, spawnY);
                    statement.setDouble(8, spawnZ);
                    statement.executeUpdate();
                }

                BountyBossMaterializationSnapshot result = readBySummon(connection, summonId).orElseThrow();
                LinkedHashMap<String, Object> data = requestMap(
                        summonId, backend, bossDefinition, entityUuid, world, spawnX, spawnY, spawnZ
                );
                data.put("materialization", snapshotMap(result));
                insertProcessed(connection, operationId, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public Optional<BountyBossMaterializationSnapshot> findBySummon(UUID summonId) throws SQLException {
        Objects.requireNonNull(summonId, "summonId");
        try (Connection connection = dataSource.getConnection()) {
            return readBySummon(connection, summonId);
        }
    }

    public Optional<BountyBossMaterializationSnapshot> findByEntity(UUID entityUuid) throws SQLException {
        Objects.requireNonNull(entityUuid, "entityUuid");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT summon_id,
                            backend_id,
                            boss_definition_id,
                            world_name,
                            spawn_x,
                            spawn_y,
                            spawn_z,
                            created_at
                     FROM bounty_boss_materializations
                     WHERE entity_uuid = ?
                     """)) {
            statement.setObject(1, entityUuid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new BountyBossMaterializationSnapshot(
                        row.getObject("summon_id", UUID.class),
                        entityUuid,
                        row.getString("backend_id"),
                        row.getString("boss_definition_id"),
                        row.getString("world_name"),
                        row.getDouble("spawn_x"),
                        row.getDouble("spawn_y"),
                        row.getDouble("spawn_z"),
                        row.getTimestamp("created_at").toInstant()
                ));
            }
        }
    }

    private static LockedSummon lockActiveSummon(Connection connection, UUID summonId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.status,
                       s.owner_backend_id,
                       c.family_id,
                       c.tier
                FROM bounty_summons s
                JOIN bounty_contracts c ON c.contract_id = s.contract_id
                WHERE s.summon_id = ?
                FOR UPDATE OF s
                """)) {
            statement.setObject(1, summonId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new BountyException("Unknown bounty summon: " + summonId);
                if (BountySummonStatus.valueOf(row.getString("status")) != BountySummonStatus.ACTIVE) {
                    throw new BountyException("Bounty boss materialization requires ACTIVE summon: " + summonId);
                }
                String ownerBackend = row.getString("owner_backend_id");
                if (ownerBackend == null || ownerBackend.isBlank()) {
                    throw new BountyException("ACTIVE bounty summon has no owner backend: " + summonId);
                }
                return new LockedSummon(
                        ownerBackend,
                        new BountyFamilyId(row.getString("family_id")),
                        row.getInt("tier")
                );
            }
        }
    }

    private static Optional<BountyBossMaterializationSnapshot> readBySummon(
            Connection connection,
            UUID summonId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT entity_uuid,
                       backend_id,
                       boss_definition_id,
                       world_name,
                       spawn_x,
                       spawn_y,
                       spawn_z,
                       created_at
                FROM bounty_boss_materializations
                WHERE summon_id = ?
                """)) {
            statement.setObject(1, summonId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new BountyBossMaterializationSnapshot(
                        summonId,
                        row.getObject("entity_uuid", UUID.class),
                        row.getString("backend_id"),
                        row.getString("boss_definition_id"),
                        row.getString("world_name"),
                        row.getDouble("spawn_x"),
                        row.getDouble("spawn_y"),
                        row.getDouble("spawn_z"),
                        row.getTimestamp("created_at").toInstant()
                ));
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
            statement.setString(2, OPERATION_TYPE);
            statement.setString(3, writeJson(data));
            statement.executeUpdate();
        }
    }

    private static LinkedHashMap<String, Object> requestMap(
            UUID summonId,
            String backendId,
            String bossDefinitionId,
            UUID entityUuid,
            String worldName,
            double spawnX,
            double spawnY,
            double spawnZ
    ) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("summon_id", summonId.toString());
        data.put("backend_id", backendId);
        data.put("boss_definition_id", bossDefinitionId);
        data.put("entity_uuid", entityUuid.toString());
        data.put("world_name", worldName);
        data.put("spawn_x", spawnX);
        data.put("spawn_y", spawnY);
        data.put("spawn_z", spawnZ);
        return data;
    }

    private static Map<String, Object> snapshotMap(BountyBossMaterializationSnapshot value) {
        LinkedHashMap<String, Object> data = requestMap(
                value.summonId(),
                value.backendId(),
                value.bossDefinitionId(),
                value.entityUuid(),
                value.worldName(),
                value.spawnX(),
                value.spawnY(),
                value.spawnZ()
        );
        data.put("created_at", value.createdAt().toString());
        return data;
    }

    private static BountyBossMaterializationSnapshot snapshotFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "materialization");
        return new BountyBossMaterializationSnapshot(
                UUID.fromString(stringValue(value, "summon_id")),
                UUID.fromString(stringValue(value, "entity_uuid")),
                stringValue(value, "backend_id"),
                stringValue(value, "boss_definition_id"),
                stringValue(value, "world_name"),
                doubleValue(value, "spawn_x"),
                doubleValue(value, "spawn_y"),
                doubleValue(value, "spawn_z"),
                java.time.Instant.parse(stringValue(value, "created_at"))
        );
    }

    private static Map<String, Object> requireType(ProcessedOperation operation, UUID operationId) {
        if (!OPERATION_TYPE.equals(operation.operationType())) {
            throw new BountyException(
                    "operation_id " + operationId + " already belongs to " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static void requireSameMaterialization(
            BountyBossMaterializationSnapshot existing,
            String backendId,
            String bossDefinitionId,
            UUID entityUuid,
            String worldName,
            double spawnX,
            double spawnY,
            double spawnZ,
            UUID summonId
    ) {
        if (!existing.backendId().equals(backendId)
                || !existing.bossDefinitionId().equals(bossDefinitionId)
                || !existing.entityUuid().equals(entityUuid)
                || !existing.worldName().equals(worldName)
                || Double.compare(existing.spawnX(), spawnX) != 0
                || Double.compare(existing.spawnY(), spawnY) != 0
                || Double.compare(existing.spawnZ(), spawnZ) != 0) {
            throw new BountyException("Bounty summon already materialized as a different entity: " + summonId);
        }
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new BountyException("Could not parse bounty boss materialization replay", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BountyException("Could not serialize bounty boss materialization replay", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new BountyException("Bounty boss materialization field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> data, String field) {
        Object raw = data.get(field);
        if (raw == null) throw new BountyException("Missing bounty boss materialization field: " + field);
        return Objects.toString(raw);
    }

    private static double doubleValue(Map<String, Object> data, String field) {
        Object raw = data.get(field);
        if (!(raw instanceof Number number)) {
            throw new BountyException("Bounty boss materialization field is not numeric: " + field);
        }
        return number.doubleValue();
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!UUID.fromString(stringValue(data, field)).equals(expected)) throw reused(operationId);
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) throw reused(operationId);
    }

    private static void requireDouble(Map<String, Object> data, String field, double expected, UUID operationId) {
        if (Double.compare(doubleValue(data, field), expected) != 0) throw reused(operationId);
    }

    private static BountyException reused(UUID operationId) {
        return new BountyException("operation_id reused with a different bounty boss materialization request: " + operationId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void requireFinite(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("spawn coordinates must be finite");
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedSummon(String ownerBackendId, BountyFamilyId familyId, int tier) { }

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(result, "result"))
            );
        }
    }
}
