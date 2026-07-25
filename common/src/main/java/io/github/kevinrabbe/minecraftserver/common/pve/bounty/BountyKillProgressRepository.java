package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Exactly-once bridge from an already-authorized managed entity kill to the player's current family bounty.
 *
 * <p>The derived progress operation is consumed even when no ACTIVE_HUNT exists. Therefore a replay of an old entity
 * kill can never migrate into a later contract for the same family.</p>
 */
public final class BountyKillProgressRepository {
    private static final String OPERATION_TYPE = "BOUNTY_MANAGED_KILL_PROGRESS";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public BountyKillProgressRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public BountyKillProgressResult recordManagedKill(
            UUID operationId,
            UUID playerId,
            BountyFamilyId familyId,
            int eligibleKills,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(familyId, "familyId");
        if (eligibleKills <= 0) {
            throw new IllegalArgumentException("eligibleKills must be > 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), operationId);
                    requireUuid(data, "player_id", playerId, operationId);
                    requireString(data, "family_id", familyId.value(), operationId);
                    requireInt(data, "eligible_kills", eligibleKills, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    BountyKillProgressResult result = resultFrom(data);
                    connection.commit();
                    return result;
                }

                Optional<BountyContractSnapshot> active = lockActiveHunt(connection, playerId, familyId);
                BountyContractSnapshot updated = null;
                if (active.isPresent()) {
                    BountyContractSnapshot current = active.orElseThrow();
                    int nextProgress;
                    try {
                        nextProgress = Math.min(
                                current.requiredEligibleKills(),
                                Math.addExact(current.eligibleKillProgress(), eligibleKills)
                        );
                    } catch (ArithmeticException ignored) {
                        nextProgress = current.requiredEligibleKills();
                    }
                    boolean ready = nextProgress == current.requiredEligibleKills();
                    BountyContractStatus nextStatus = ready
                            ? BountyContractStatus.SUMMON_READY
                            : BountyContractStatus.ACTIVE_HUNT;
                    int summonAuthorizations = ready ? 1 : 0;
                    long nextVersion = incrementVersion(current.stateVersion(), current.contractId());

                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE bounty_contracts
                            SET eligible_kill_progress = ?,
                                status = ?,
                                summon_authorizations_remaining = ?,
                                state_version = ?,
                                updated_at = NOW()
                            WHERE contract_id = ? AND state_version = ? AND status = 'ACTIVE_HUNT'
                            """)) {
                        statement.setInt(1, nextProgress);
                        statement.setString(2, nextStatus.name());
                        statement.setInt(3, summonAuthorizations);
                        statement.setLong(4, nextVersion);
                        statement.setObject(5, current.contractId());
                        statement.setLong(6, current.stateVersion());
                        if (statement.executeUpdate() != 1) {
                            throw new BountyException("Bounty contract changed concurrently while recording managed kill");
                        }
                    }
                    updated = readContract(connection, current.contractId());
                }

                BountyKillProgressResult result = new BountyKillProgressResult(
                        playerId,
                        familyId,
                        eligibleKills,
                        updated
                );
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("player_id", playerId.toString());
                data.put("family_id", familyId.value());
                data.put("eligible_kills", eligibleKills);
                data.put("reason", normalizedReason);
                data.put("applied", result.applied());
                data.put("contract", updated == null ? null : contractMap(updated));
                insertProcessed(connection, operationId, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public static UUID progressOperationId(UUID resourceKillOperationId) {
        Objects.requireNonNull(resourceKillOperationId, "resourceKillOperationId");
        return UUID.nameUUIDFromBytes(
                ("bounty-managed-kill:" + resourceKillOperationId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Optional<BountyContractSnapshot> lockActiveHunt(
            Connection connection,
            UUID playerId,
            BountyFamilyId familyId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT contract_id,
                       tier,
                       eligible_kill_progress,
                       required_eligible_kills,
                       summon_authorizations_remaining,
                       state_version
                FROM bounty_contracts
                WHERE player_id = ?
                  AND family_id = ?
                  AND status = 'ACTIVE_HUNT'
                ORDER BY contract_id
                FOR UPDATE
                """)) {
            statement.setObject(1, playerId);
            statement.setString(2, familyId.value());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                BountyContractSnapshot result = new BountyContractSnapshot(
                        rows.getObject("contract_id", UUID.class),
                        playerId,
                        familyId,
                        rows.getInt("tier"),
                        BountyContractStatus.ACTIVE_HUNT,
                        rows.getInt("eligible_kill_progress"),
                        rows.getInt("required_eligible_kills"),
                        rows.getInt("summon_authorizations_remaining"),
                        rows.getLong("state_version")
                );
                if (rows.next()) {
                    throw new BountyException("Multiple ACTIVE_HUNT contracts exist for player/family");
                }
                return Optional.of(result);
            }
        }
    }

    private static BountyContractSnapshot readContract(Connection connection, UUID contractId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id,
                       family_id,
                       tier,
                       status,
                       eligible_kill_progress,
                       required_eligible_kills,
                       summon_authorizations_remaining,
                       state_version
                FROM bounty_contracts
                WHERE contract_id = ?
                """)) {
            statement.setObject(1, contractId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new BountyException("Unknown bounty contract: " + contractId);
                return new BountyContractSnapshot(
                        contractId,
                        row.getObject("player_id", UUID.class),
                        new BountyFamilyId(row.getString("family_id")),
                        row.getInt("tier"),
                        BountyContractStatus.valueOf(row.getString("status")),
                        row.getInt("eligible_kill_progress"),
                        row.getInt("required_eligible_kills"),
                        row.getInt("summon_authorizations_remaining"),
                        row.getLong("state_version")
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

    private static Map<String, Object> contractMap(BountyContractSnapshot contract) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("contract_id", contract.contractId().toString());
        value.put("player_id", contract.playerId().toString());
        value.put("family_id", contract.familyId().value());
        value.put("tier", contract.tier());
        value.put("status", contract.status().name());
        value.put("eligible_kill_progress", contract.eligibleKillProgress());
        value.put("required_eligible_kills", contract.requiredEligibleKills());
        value.put("summon_authorizations_remaining", contract.summonAuthorizationsRemaining());
        value.put("state_version", contract.stateVersion());
        return value;
    }

    private static BountyContractSnapshot contractFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "contract");
        return new BountyContractSnapshot(
                UUID.fromString(stringValue(value, "contract_id")),
                UUID.fromString(stringValue(value, "player_id")),
                new BountyFamilyId(stringValue(value, "family_id")),
                intValue(value, "tier"),
                BountyContractStatus.valueOf(stringValue(value, "status")),
                intValue(value, "eligible_kill_progress"),
                intValue(value, "required_eligible_kills"),
                intValue(value, "summon_authorizations_remaining"),
                longValue(value, "state_version")
        );
    }

    private static BountyKillProgressResult resultFrom(Map<String, Object> data) {
        UUID playerId = UUID.fromString(stringValue(data, "player_id"));
        BountyFamilyId familyId = new BountyFamilyId(stringValue(data, "family_id"));
        int eligibleKills = intValue(data, "eligible_kills");
        boolean applied = booleanValue(data, "applied");
        Object rawContract = data.get("contract");
        if (applied != (rawContract != null)) {
            throw new BountyException("Invalid persisted bounty managed-kill result shape");
        }
        return new BountyKillProgressResult(
                playerId,
                familyId,
                eligibleKills,
                rawContract == null ? null : contractFrom(rawContract)
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

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new BountyException("Could not parse bounty managed-kill idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BountyException("Could not serialize bounty managed-kill idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new BountyException("Bounty managed-kill field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> data, String field) {
        Object raw = data.get(field);
        if (raw == null) throw new BountyException("Missing bounty managed-kill field: " + field);
        return Objects.toString(raw);
    }

    private static long longValue(Map<String, Object> data, String field) {
        Object raw = data.get(field);
        if (!(raw instanceof Number number)) {
            throw new BountyException("Bounty managed-kill field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static int intValue(Map<String, Object> data, String field) {
        long value = longValue(data, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new BountyException("Bounty managed-kill field is outside integer range: " + field);
        }
        return (int) value;
    }

    private static boolean booleanValue(Map<String, Object> data, String field) {
        Object raw = data.get(field);
        if (!(raw instanceof Boolean value)) {
            throw new BountyException("Bounty managed-kill field is not boolean: " + field);
        }
        return value;
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!UUID.fromString(stringValue(data, field)).equals(expected)) throw reused(operationId);
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) throw reused(operationId);
    }

    private static void requireInt(Map<String, Object> data, String field, int expected, UUID operationId) {
        if (intValue(data, field) != expected) throw reused(operationId);
    }

    private static BountyException reused(UUID operationId) {
        return new BountyException("operation_id reused with a different bounty managed-kill request: " + operationId);
    }

    private static long incrementVersion(long current, UUID contractId) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new BountyException("bounty contract state_version overflow: " + contractId, exception);
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
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

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Map.copyOf(Objects.requireNonNull(result, "result"));
        }
    }
}
