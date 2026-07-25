package io.github.kevinrabbe.minecraftserver.common.pvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.economy.UniqueItemEscrowValidator;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Moves exact individualized MMO gear into WAR_CUSTODY and exposes read-only combat snapshots to the disposable
 * 1.8.9 runtime. The runtime never receives authority over the persistent item itself.
 */
public final class ClanWarLoadoutRepository {
    private static final String DEPOSIT_OPERATION = "CLAN_WAR_ITEM_DEPOSIT";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final UniqueItemEscrowValidator itemRemovalValidator;
    private final PlayerStateRepository playerStates;

    public ClanWarLoadoutRepository(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            UniqueItemEscrowValidator itemRemovalValidator
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.itemRemovalValidator = Objects.requireNonNull(itemRemovalValidator, "itemRemovalValidator");
        this.playerStates = new PlayerStateRepository(dataSource);
    }

    public ClanWarCustodyDepositResult depositPlayerItem(
            UUID operationId,
            UUID warId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            UUID itemInstanceId,
            long expectedItemStateVersion,
            String logicalZoneId,
            String entryPoint,
            byte[] nextPlayerStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(warId, "warId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        Objects.requireNonNull(nextPlayerStatePayload, "nextPlayerStatePayload");
        if (expectedPlayerStateVersion < 0 || expectedItemStateVersion < 0) {
            throw new IllegalArgumentException("expected state versions must be >= 0");
        }
        String backend = requireNonBlank(backendId, "backendId");
        String zone = normalizeOptional(logicalZoneId);
        String entry = normalizeOptional(entryPoint);
        String normalizedReason = requireReason(reason);
        String payloadHash = sha256(nextPlayerStatePayload);
        Map<String, Object> request = requestMap(
                "war_id", warId,
                "session_id", sessionId,
                "backend_id", backend,
                "expected_player_state_version", expectedPlayerStateVersion,
                "item_instance_id", itemInstanceId,
                "expected_item_state_version", expectedItemStateVersion,
                "logical_zone_id", zone,
                "entry_point", entry,
                "payload_sha256", payloadHash,
                "reason", normalizedReason
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ClanWarCustodyDepositResult replay = resultFrom(requireReplay(
                            processed.orElseThrow(), request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                requireRosterLockedWar(connection, warId);
                UUID playerId = playerIdForSession(connection, sessionId);
                requireLiveRosterPlayer(connection, warId, playerId);
                LockedItem item = lockItem(connection, itemInstanceId);
                requireIndividual(item.definitionId());
                if (item.stateVersion() != expectedItemStateVersion) {
                    throw new ClanWarException("stale item state_version for war custody: " + itemInstanceId);
                }
                if (!"PLAYER_INVENTORY".equals(item.locationKind()) || !playerId.equals(item.locationId())) {
                    throw new ClanWarException("war item is not in authoritative player inventory custody");
                }

                long nextPlayerStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        backend,
                        expectedPlayerStateVersion,
                        zone,
                        entry,
                        nextPlayerStatePayload,
                        (lockedPlayerId, currentPayload, nextPayload) -> {
                            if (!lockedPlayerId.equals(playerId)) {
                                throw new ClanWarException("session player changed during war custody deposit");
                            }
                            itemRemovalValidator.verifyRemoval(
                                    lockedPlayerId,
                                    itemInstanceId,
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                long custodyVersion = increment(item.stateVersion(), "war item", itemInstanceId);
                moveItemToWar(connection, itemInstanceId, playerId, warId, item.stateVersion(), custodyVersion);
                insertWarItem(connection, warId, playerId, itemInstanceId, custodyVersion);
                insertProvenance(
                        connection,
                        itemInstanceId,
                        custodyVersion,
                        operationId,
                        playerId,
                        warId,
                        normalizedReason
                );

                ClanWarCustodiedItemSnapshot snapshot = readActiveItem(connection, warId, itemInstanceId)
                        .orElseThrow(() -> new ClanWarException("war custody item disappeared after deposit"));
                ClanWarCustodyDepositResult result = new ClanWarCustodyDepositResult(snapshot, nextPlayerStateVersion);
                insertProcessed(connection, operationId, request, resultMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public List<ClanWarCustodiedItemSnapshot> loadActiveCombatSnapshot(UUID warId) throws SQLException {
        Objects.requireNonNull(warId, "warId");
        try (Connection connection = dataSource.getConnection()) {
            requireWar(connection, warId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT w.war_id,
                           w.player_id,
                           w.item_instance_id,
                           i.definition_id,
                           i.state_version,
                           i.roll_state::text AS roll_state_json,
                           i.upgrade_level
                    FROM clan_war_items w
                    JOIN item_instances i ON i.item_instance_id = w.item_instance_id
                    WHERE w.war_id = ?
                      AND w.released_at IS NULL
                      AND i.location_kind = 'WAR_CUSTODY'
                      AND i.location_id = w.war_id
                      AND i.state_version = w.entry_item_version
                    ORDER BY w.player_id, w.item_instance_id
                    """)) {
                statement.setObject(1, warId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<ClanWarCustodiedItemSnapshot> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(snapshot(rows));
                    }
                    return List.copyOf(result);
                }
            }
        }
    }

    private ItemDefinition requireIndividual(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new ClanWarException("Clan War custody requires INDIVIDUAL item: " + definitionId);
        }
        return definition;
    }

    private static void requireRosterLockedWar(Connection connection, UUID warId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status FROM clan_wars WHERE war_id = ? FOR UPDATE
                """)) {
            statement.setObject(1, warId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanWarException("Unknown clan war: " + warId);
                }
                ClanWarStatus status = ClanWarStatus.valueOf(row.getString("status"));
                if (status != ClanWarStatus.ROSTER_LOCKED) {
                    throw new ClanWarException("war items may enter custody only while ROSTER_LOCKED: " + status);
                }
            }
        }
    }

    private static void requireWar(Connection connection, UUID warId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM clan_wars WHERE war_id = ?")) {
            statement.setObject(1, warId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ClanWarException("Unknown clan war: " + warId);
            }
        }
    }

    private static UUID playerIdForSession(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id FROM player_sessions WHERE network_session_id = ?
                """)) {
            statement.setObject(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ClanWarException("Unknown player session: " + sessionId);
                return row.getObject("player_id", UUID.class);
            }
        }
    }

    private static void requireLiveRosterPlayer(Connection connection, UUID warId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM clan_war_rosters
                WHERE war_id = ? AND player_id = ? AND released_at IS NULL
                """)) {
            statement.setObject(1, warId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanWarException("player is not on the locked live war roster: " + playerId);
                }
            }
        }
    }

    private static LockedItem lockItem(Connection connection, UUID itemInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT definition_id, location_kind, location_id, state_version
                FROM item_instances
                WHERE item_instance_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ClanWarException("Unknown war item: " + itemInstanceId);
                return new LockedItem(
                        row.getString("definition_id"),
                        row.getString("location_kind"),
                        row.getObject("location_id", UUID.class),
                        row.getLong("state_version")
                );
            }
        }
    }

    private static void moveItemToWar(
            Connection connection,
            UUID itemInstanceId,
            UUID playerId,
            UUID warId,
            long expectedVersion,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = 'WAR_CUSTODY',
                    location_id = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ?
                  AND location_kind = 'PLAYER_INVENTORY'
                  AND location_id = ?
                  AND state_version = ?
                """)) {
            statement.setObject(1, warId);
            statement.setLong(2, nextVersion);
            statement.setObject(3, itemInstanceId);
            statement.setObject(4, playerId);
            statement.setLong(5, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new ClanWarException("war item custody changed concurrently: " + itemInstanceId);
            }
        }
    }

    private static void insertWarItem(
            Connection connection,
            UUID warId,
            UUID playerId,
            UUID itemInstanceId,
            long custodyVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO clan_war_items(war_id, player_id, item_instance_id, entry_item_version)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setObject(1, warId);
            statement.setObject(2, playerId);
            statement.setObject(3, itemInstanceId);
            statement.setLong(4, custodyVersion);
            statement.executeUpdate();
        }
    }

    private static void insertProvenance(
            Connection connection,
            UUID itemInstanceId,
            long sequenceNo,
            UUID operationId,
            UUID playerId,
            UUID warId,
            String reason
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
                    actor_player_id
                ) VALUES (?, ?, ?, 'MOVED', 'PLAYER_INVENTORY', ?, 'WAR_CUSTODY', ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, sequenceNo);
            statement.setObject(3, operationId);
            statement.setObject(4, playerId);
            statement.setObject(5, warId);
            statement.setString(6, reason);
            statement.setObject(7, playerId);
            statement.executeUpdate();
        }
    }

    private static Optional<ClanWarCustodiedItemSnapshot> readActiveItem(
            Connection connection,
            UUID warId,
            UUID itemInstanceId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT w.war_id,
                       w.player_id,
                       w.item_instance_id,
                       i.definition_id,
                       i.state_version,
                       i.roll_state::text AS roll_state_json,
                       i.upgrade_level
                FROM clan_war_items w
                JOIN item_instances i ON i.item_instance_id = w.item_instance_id
                WHERE w.war_id = ?
                  AND w.item_instance_id = ?
                  AND w.released_at IS NULL
                  AND i.location_kind = 'WAR_CUSTODY'
                  AND i.location_id = w.war_id
                  AND i.state_version = w.entry_item_version
                """)) {
            statement.setObject(1, warId);
            statement.setObject(2, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(snapshot(row)) : Optional.empty();
            }
        }
    }

    private static ClanWarCustodiedItemSnapshot snapshot(ResultSet row) throws SQLException {
        return new ClanWarCustodiedItemSnapshot(
                row.getObject("war_id", UUID.class),
                row.getObject("player_id", UUID.class),
                row.getObject("item_instance_id", UUID.class),
                row.getString("definition_id"),
                row.getLong("state_version"),
                row.getString("roll_state_json"),
                row.getInt("upgrade_level")
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
                        row.getString("operation_type"),
                        readJsonMap(row.getString("result_json"))
                ));
            }
        }
    }

    private static Object requireReplay(
            ProcessedOperation processed,
            Map<String, Object> request,
            UUID operationId
    ) {
        if (!DEPOSIT_OPERATION.equals(processed.operationType())) {
            throw new ClanWarException("operation_id already belongs to " + processed.operationType());
        }
        if (!objectMap(processed.data().get("request"), "request").equals(request)) {
            throw new ClanWarException("operation_id reused with a different war-custody request: " + operationId);
        }
        Object result = processed.data().get("result");
        if (result == null) throw new ClanWarException("processed war-custody operation is missing result");
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
            statement.setString(2, DEPOSIT_OPERATION);
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

    private static Map<String, Object> resultMap(ClanWarCustodyDepositResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("war_id", result.item().warId().toString());
        value.put("player_id", result.item().playerId().toString());
        value.put("item_instance_id", result.item().itemInstanceId().toString());
        value.put("definition_id", result.item().definitionId());
        value.put("item_state_version", result.item().itemStateVersion());
        value.put("roll_state_json", result.item().rollStateJson());
        value.put("upgrade_level", result.item().upgradeLevel());
        value.put("player_state_version", result.playerStateVersion());
        return value;
    }

    private static ClanWarCustodyDepositResult resultFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        ClanWarCustodiedItemSnapshot item = new ClanWarCustodiedItemSnapshot(
                uuidValue(value, "war_id"),
                uuidValue(value, "player_id"),
                uuidValue(value, "item_instance_id"),
                stringValue(value, "definition_id"),
                longValue(value, "item_state_version"),
                stringValue(value, "roll_state_json"),
                intValue(value, "upgrade_level")
        );
        return new ClanWarCustodyDepositResult(item, longValue(value, "player_state_version"));
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ClanWarException("Could not parse war-custody idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ClanWarException("Could not serialize war-custody idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) throw new ClanWarException("war-custody field is not an object: " + field);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) throw new ClanWarException("missing war-custody field: " + field);
        return Objects.toString(raw);
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
            throw new ClanWarException("invalid numeric war-custody field: " + field, exception);
        }
    }

    private static long increment(long current, String authority, Object id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new ClanWarException(authority + " state_version overflow: " + id, exception);
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

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedItem(String definitionId, String locationKind, UUID locationId, long stateVersion) { }

    private record ProcessedOperation(String operationType, Map<String, Object> data) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            data = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
        }
    }
}
