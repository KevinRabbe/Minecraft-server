package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Player-owned bounty pouch read/withdraw authority.
 *
 * <p>Boss settlement may accumulate family materials here without depending on live inventory capacity. Withdrawal
 * atomically decrements exactly one pouch balance and reserves one ordinary pending commodity delivery, making bounty
 * materials usable by the normal inventory/Bazaar economy without introducing a second delivery mechanism.</p>
 */
public final class BountyPouchRepository {
    private static final String WITHDRAW_OPERATION = "BOUNTY_POUCH_WITHDRAW";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;

    public BountyPouchRepository(DataSource dataSource, ItemCatalog itemCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
    }

    public List<BountyPouchBalanceSnapshot> listBalances(UUID playerId, BountyFamilyId familyId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(familyId, "familyId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT commodity_definition_id, quantity, state_version, updated_at
                     FROM bounty_pouch_balances
                     WHERE player_id = ? AND family_id = ?
                     ORDER BY commodity_definition_id
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, familyId.value());
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<BountyPouchBalanceSnapshot> result = new ArrayList<>();
                while (rows.next()) {
                    String commodity = requireCommodity(rows.getString("commodity_definition_id")).definitionId();
                    result.add(new BountyPouchBalanceSnapshot(
                            playerId,
                            familyId,
                            commodity,
                            rows.getLong("quantity"),
                            rows.getLong("state_version"),
                            rows.getTimestamp("updated_at").toInstant()
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    public Optional<BountyPouchBalanceSnapshot> loadBalance(
            UUID playerId,
            BountyFamilyId familyId,
            String commodityDefinitionId
    ) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(familyId, "familyId");
        String commodity = requireCommodity(commodityDefinitionId).definitionId();
        try (Connection connection = dataSource.getConnection()) {
            return readBalance(connection, playerId, familyId, commodity, false);
        }
    }

    public BountyPouchWithdrawalResult withdraw(
            UUID operationId,
            UUID playerId,
            BountyFamilyId familyId,
            String commodityDefinitionId,
            long quantity,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(familyId, "familyId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        String commodity = requireCommodity(commodityDefinitionId).definitionId();
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
                    requireString(data, "commodity_definition_id", commodity, operationId);
                    requireLong(data, "quantity", quantity, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    BountyPouchWithdrawalResult replay = withdrawalFrom(data.get("result"));
                    connection.commit();
                    return replay;
                }

                lockPouch(connection, playerId, familyId);
                BountyPouchBalanceSnapshot current = readBalance(
                        connection, playerId, familyId, commodity, true
                ).orElseThrow(() -> new BountyException(
                        "Bounty pouch does not contain material: " + commodity
                ));
                if (current.quantity() < quantity) {
                    throw new BountyException("Insufficient bounty pouch quantity for " + commodity);
                }

                long nextVersion = incrementVersion(current.stateVersion(), playerId, familyId, commodity);
                long nextQuantity = current.quantity() - quantity;
                BountyPouchBalanceSnapshot updated = updateBalance(
                        connection, current, nextQuantity, nextVersion
                );

                UUID deliveryId = deterministicUuid(operationId, "bounty-pouch-delivery");
                insertPendingDelivery(connection, deliveryId, playerId, commodity, quantity, operationId);

                BountyPouchWithdrawalResult result = new BountyPouchWithdrawalResult(updated, quantity, deliveryId);
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("player_id", playerId.toString());
                data.put("family_id", familyId.value());
                data.put("commodity_definition_id", commodity);
                data.put("quantity", quantity);
                data.put("reason", normalizedReason);
                data.put("result", withdrawalMap(result));
                insertProcessed(connection, operationId, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private ItemDefinition requireCommodity(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
            throw new BountyException("Bounty pouch material must be a COMMODITY definition: " + definitionId);
        }
        return definition;
    }

    private static void lockPouch(Connection connection, UUID playerId, BountyFamilyId familyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM bounty_pouches
                WHERE player_id = ? AND family_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, playerId);
            statement.setString(2, familyId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new BountyException("Bounty pouch does not exist for family " + familyId.value());
                }
            }
        }
    }

    private static Optional<BountyPouchBalanceSnapshot> readBalance(
            Connection connection,
            UUID playerId,
            BountyFamilyId familyId,
            String commodity,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT quantity, state_version, updated_at
                FROM bounty_pouch_balances
                WHERE player_id = ? AND family_id = ? AND commodity_definition_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            statement.setString(2, familyId.value());
            statement.setString(3, commodity);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new BountyPouchBalanceSnapshot(
                        playerId,
                        familyId,
                        commodity,
                        row.getLong("quantity"),
                        row.getLong("state_version"),
                        row.getTimestamp("updated_at").toInstant()
                ));
            }
        }
    }

    private static BountyPouchBalanceSnapshot updateBalance(
            Connection connection,
            BountyPouchBalanceSnapshot current,
            long nextQuantity,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bounty_pouch_balances
                SET quantity = ?, state_version = ?, updated_at = NOW()
                WHERE player_id = ?
                  AND family_id = ?
                  AND commodity_definition_id = ?
                  AND state_version = ?
                RETURNING updated_at
                """)) {
            statement.setLong(1, nextQuantity);
            statement.setLong(2, nextVersion);
            statement.setObject(3, current.playerId());
            statement.setString(4, current.familyId().value());
            statement.setString(5, current.commodityDefinitionId());
            statement.setLong(6, current.stateVersion());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new BountyException("Bounty pouch balance changed concurrently");
                }
                return new BountyPouchBalanceSnapshot(
                        current.playerId(),
                        current.familyId(),
                        current.commodityDefinitionId(),
                        nextQuantity,
                        nextVersion,
                        row.getTimestamp("updated_at").toInstant()
                );
            }
        }
    }

    private static void insertPendingDelivery(
            Connection connection,
            UUID deliveryId,
            UUID playerId,
            String commodity,
            long quantity,
            UUID sourceOperationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_commodity_deliveries(
                    delivery_id, player_id, commodity_definition_id, quantity, source_operation_id, status
                ) VALUES (?, ?, ?, ?, ?, 'PENDING')
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, playerId);
            statement.setString(3, commodity);
            statement.setLong(4, quantity);
            statement.setObject(5, sourceOperationId);
            statement.executeUpdate();
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
            statement.setString(2, WITHDRAW_OPERATION);
            statement.setString(3, writeJson(data));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> withdrawalMap(BountyPouchWithdrawalResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("player_id", result.balance().playerId().toString());
        value.put("family_id", result.balance().familyId().value());
        value.put("commodity_definition_id", result.balance().commodityDefinitionId());
        value.put("remaining_quantity", result.balance().quantity());
        value.put("state_version", result.balance().stateVersion());
        value.put("updated_at", result.balance().updatedAt().toString());
        value.put("withdrawn_quantity", result.withdrawnQuantity());
        value.put("delivery_id", result.deliveryId().toString());
        return value;
    }

    private static BountyPouchWithdrawalResult withdrawalFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        BountyPouchBalanceSnapshot balance = new BountyPouchBalanceSnapshot(
                UUID.fromString(stringValue(value, "player_id")),
                new BountyFamilyId(stringValue(value, "family_id")),
                stringValue(value, "commodity_definition_id"),
                longValue(value, "remaining_quantity"),
                longValue(value, "state_version"),
                Instant.parse(stringValue(value, "updated_at"))
        );
        return new BountyPouchWithdrawalResult(
                balance,
                longValue(value, "withdrawn_quantity"),
                UUID.fromString(stringValue(value, "delivery_id"))
        );
    }

    private static Map<String, Object> requireType(ProcessedOperation operation, UUID operationId) {
        if (!WITHDRAW_OPERATION.equals(operation.operationType())) {
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
            throw new BountyException("Could not parse bounty pouch idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BountyException("Could not serialize bounty pouch idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new BountyException("Bounty pouch idempotency field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> data, String field) {
        Object raw = data.get(field);
        if (raw == null) throw new BountyException("Missing bounty pouch idempotency field: " + field);
        return Objects.toString(raw);
    }

    private static long longValue(Map<String, Object> data, String field) {
        Object raw = data.get(field);
        if (!(raw instanceof Number number)) {
            throw new BountyException("Bounty pouch idempotency field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!UUID.fromString(stringValue(data, field)).equals(expected)) throw reused(operationId);
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) throw reused(operationId);
    }

    private static void requireLong(Map<String, Object> data, String field, long expected, UUID operationId) {
        if (longValue(data, field) != expected) throw reused(operationId);
    }

    private static BountyException reused(UUID operationId) {
        return new BountyException("operation_id reused with a different bounty pouch request: " + operationId);
    }

    private static long incrementVersion(
            long current,
            UUID playerId,
            BountyFamilyId familyId,
            String commodity
    ) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new BountyException(
                    "Bounty pouch state_version overflow for " + playerId + "/" + familyId.value() + "/" + commodity,
                    exception
            );
        }
    }

    private static UUID deterministicUuid(UUID operationId, String suffix) {
        return UUID.nameUUIDFromBytes((operationId + ":" + suffix).getBytes(StandardCharsets.UTF_8));
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

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Map.copyOf(Objects.requireNonNull(result, "result"));
        }
    }
}
