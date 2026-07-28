package io.github.kevinrabbe.minecraftserver.common.economy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import io.github.kevinrabbe.minecraftserver.common.session.SessionStatus;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Irreversible unique-item salvage authority with configured low-value returns. */
public final class SalvageRepository {
    private static final String OPERATION = "UNIQUE_ITEM_SALVAGE";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final SalvageCatalog salvageCatalog;
    private final UniqueItemEscrowValidator itemValidator;
    private final PlayerStateRepository playerStates;

    public SalvageRepository(
            DataSource dataSource,
            SalvageCatalog salvageCatalog,
            UniqueItemEscrowValidator itemValidator
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.salvageCatalog = Objects.requireNonNull(salvageCatalog, "salvageCatalog");
        this.itemValidator = Objects.requireNonNull(itemValidator, "itemValidator");
        this.playerStates = new PlayerStateRepository(dataSource);
    }

    public SalvageResult salvage(
            UUID operationId,
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
                    requireUuid(data, "item_instance_id", itemInstanceId, operationId);
                    requireLong(data, "expected_item_state_version", expectedItemStateVersion, operationId);
                    requireNullableString(data, "logical_zone_id", zone, operationId);
                    requireNullableString(data, "entry_point", entry, operationId);
                    requireString(data, "payload_sha256", payloadHash, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    SalvageResult result = resultFrom(data.get("result"));
                    connection.commit();
                    return result;
                }

                LiveSession session = lockLiveSession(
                        connection,
                        sessionId,
                        backend,
                        expectedPlayerStateVersion
                );
                LockedItem item = lockItem(connection, itemInstanceId);
                if (item.stateVersion() != expectedItemStateVersion) {
                    throw new SalvageException("Stale item state_version for salvage: " + itemInstanceId);
                }
                if (!"PLAYER_INVENTORY".equals(item.locationKind())
                        || !session.playerId().equals(item.locationId())) {
                    throw new SalvageException("Player does not own authoritative inventory custody for salvage item");
                }
                SalvageDefinition definition = salvageCatalog.require(item.definitionId());

                long playerStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        backend,
                        expectedPlayerStateVersion,
                        zone,
                        entry,
                        nextPlayerStatePayload,
                        (lockedPlayerId, currentPayload, nextPayload) -> {
                            if (!lockedPlayerId.equals(session.playerId())) {
                                throw new SalvageException("session player changed during salvage");
                            }
                            itemValidator.verifyRemoval(
                                    lockedPlayerId,
                                    itemInstanceId,
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                long destroyedVersion = increment(item.stateVersion(), "item", itemInstanceId);
                destroyItem(connection, itemInstanceId, item.stateVersion(), destroyedVersion);
                insertProvenance(
                        connection,
                        itemInstanceId,
                        destroyedVersion,
                        operationId,
                        session.playerId(),
                        normalizedReason
                );

                int ledgerLine = 0;
                insertLedger(
                        connection,
                        operationId,
                        ledgerLine++,
                        session.playerId(),
                        "ITEM_INSTANCE",
                        itemInstanceId.toString(),
                        1,
                        "DEBIT",
                        normalizedReason
                );

                CoinWalletSnapshot wallet = readWallet(connection, session.playerId(), true);
                long walletBalance = wallet.balanceMinor();
                long walletVersion = wallet.stateVersion();
                if (definition.coinReturnMinor() > 0) {
                    walletBalance = addExact(walletBalance, definition.coinReturnMinor(), "salvage Coin return overflow");
                    walletVersion = increment(walletVersion, "wallet", session.playerId());
                    updateWallet(
                            connection,
                            session.playerId(),
                            wallet.stateVersion(),
                            walletBalance,
                            walletVersion
                    );
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            session.playerId(),
                            CoinCurrency.LEDGER_ASSET_TYPE,
                            CoinCurrency.LEDGER_ASSET_ID,
                            definition.coinReturnMinor(),
                            "CREDIT",
                            normalizedReason
                    );
                }

                List<SalvageCommodityReturn> commodityReturns = new ArrayList<>();
                int ordinal = 0;
                for (Map.Entry<String, Long> returned : definition.commodityReturns().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList()) {
                    UUID deliveryId = deterministicUuid(operationId, "commodity-delivery", ordinal);
                    UUID sourceOperationId = deterministicUuid(operationId, "commodity-source", ordinal);
                    insertCommodityDelivery(
                            connection,
                            deliveryId,
                            session.playerId(),
                            returned.getKey(),
                            returned.getValue(),
                            sourceOperationId
                    );
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            session.playerId(),
                            "COMMODITY",
                            returned.getKey(),
                            returned.getValue(),
                            "CREDIT",
                            normalizedReason
                    );
                    commodityReturns.add(new SalvageCommodityReturn(
                            deliveryId,
                            returned.getKey(),
                            returned.getValue()
                    ));
                    ordinal++;
                }

                UUID salvageId = UUID.randomUUID();
                Instant createdAt = insertSalvageRecord(
                        connection,
                        salvageId,
                        operationId,
                        session.playerId(),
                        item,
                        destroyedVersion,
                        definition
                );
                SalvageResult result = new SalvageResult(
                        salvageId,
                        operationId,
                        session.playerId(),
                        itemInstanceId,
                        item.definitionId(),
                        destroyedVersion,
                        definition.coinReturnMinor(),
                        walletBalance,
                        walletVersion,
                        playerStateVersion,
                        commodityReturns,
                        createdAt
                );

                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("session_id", sessionId.toString());
                data.put("backend_id", backend);
                data.put("expected_player_state_version", expectedPlayerStateVersion);
                data.put("item_instance_id", itemInstanceId.toString());
                data.put("expected_item_state_version", expectedItemStateVersion);
                data.put("logical_zone_id", zone);
                data.put("entry_point", entry);
                data.put("payload_sha256", payloadHash);
                data.put("reason", normalizedReason);
                data.put("result", resultMap(result));
                insertProcessed(connection, operationId, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
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
                long stateVersion = row.getLong("state_version");
                SessionStatus status = SessionStatus.valueOf(row.getString("status"));
                boolean leaseValid = row.getBoolean("lease_valid");
                if (!backendId.equals(ownerBackendId)
                        || stateVersion != expectedStateVersion
                        || !leaseValid
                        || (status != SessionStatus.ACTIVE && status != SessionStatus.RECOVERING)) {
                    throw new SessionConflictException("salvage does not match authoritative live session");
                }
                return new LiveSession(playerId);
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
                if (!row.next()) {
                    throw new SalvageException("Unknown item_instance_id: " + itemInstanceId);
                }
                return new LockedItem(
                        itemInstanceId,
                        row.getString("definition_id"),
                        row.getString("location_kind"),
                        row.getObject("location_id", UUID.class),
                        row.getLong("state_version")
                );
            }
        }
    }

    private static void destroyItem(
            Connection connection,
            UUID itemInstanceId,
            long expectedVersion,
            long destroyedVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = 'DESTROYED',
                    location_id = NULL,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ?
                  AND state_version = ?
                  AND location_kind = 'PLAYER_INVENTORY'
                """)) {
            statement.setLong(1, destroyedVersion);
            statement.setObject(2, itemInstanceId);
            statement.setLong(3, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new SalvageException("item authority changed concurrently during salvage");
            }
        }
    }

    private static void insertProvenance(
            Connection connection,
            UUID itemInstanceId,
            long sequenceNo,
            UUID operationId,
            UUID playerId,
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
                ) VALUES (?, ?, ?, 'DESTROYED', 'PLAYER_INVENTORY', ?, 'DESTROYED', NULL, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, sequenceNo);
            statement.setObject(3, operationId);
            statement.setObject(4, playerId);
            statement.setString(5, reason);
            statement.setObject(6, playerId);
            statement.executeUpdate();
        }
    }

    private static CoinWalletSnapshot readWallet(Connection connection, UUID playerId, boolean forUpdate)
            throws SQLException {
        String sql = "SELECT balance_minor, state_version FROM wallets WHERE player_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SalvageException("Wallet does not exist for player_id " + playerId);
                }
                return new CoinWalletSnapshot(playerId, row.getLong(1), row.getLong(2));
            }
        }
    }

    private static void updateWallet(
            Connection connection,
            UUID playerId,
            long expectedVersion,
            long nextBalance,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wallets
                SET balance_minor = ?, state_version = ?, updated_at = NOW()
                WHERE player_id = ? AND state_version = ?
                """)) {
            statement.setLong(1, nextBalance);
            statement.setLong(2, nextVersion);
            statement.setObject(3, playerId);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new SalvageException("wallet changed concurrently during salvage");
            }
        }
    }

    private static void insertCommodityDelivery(
            Connection connection,
            UUID deliveryId,
            UUID playerId,
            String definitionId,
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
            statement.setString(3, definitionId);
            statement.setLong(4, quantity);
            statement.setObject(5, sourceOperationId);
            statement.executeUpdate();
        }
    }

    private static void insertLedger(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            String assetType,
            String assetId,
            long amount,
            String direction,
            String reason
    ) throws SQLException {
        if (amount <= 0) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            statement.setObject(3, playerId);
            statement.setString(4, assetType);
            statement.setString(5, assetId);
            statement.setLong(6, amount);
            statement.setString(7, direction);
            statement.setString(8, reason);
            statement.executeUpdate();
        }
    }

    private static Instant insertSalvageRecord(
            Connection connection,
            UUID salvageId,
            UUID operationId,
            UUID playerId,
            LockedItem item,
            long destroyedVersion,
            SalvageDefinition definition
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO salvage_records(
                    salvage_id,
                    operation_id,
                    player_id,
                    item_instance_id,
                    item_definition_id,
                    destroyed_item_version,
                    coin_return_minor,
                    commodity_returns
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING created_at
                """)) {
            statement.setObject(1, salvageId);
            statement.setObject(2, operationId);
            statement.setObject(3, playerId);
            statement.setObject(4, item.itemInstanceId());
            statement.setString(5, item.definitionId());
            statement.setLong(6, destroyedVersion);
            statement.setLong(7, definition.coinReturnMinor());
            statement.setString(8, writeJson(definition.commodityReturns()));
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getTimestamp("created_at").toInstant();
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
            statement.setString(2, OPERATION);
            statement.setString(3, writeJson(data));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> resultMap(SalvageResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("salvage_id", result.salvageId().toString());
        value.put("operation_id", result.operationId().toString());
        value.put("player_id", result.playerId().toString());
        value.put("item_instance_id", result.itemInstanceId().toString());
        value.put("item_definition_id", result.itemDefinitionId());
        value.put("destroyed_item_version", result.destroyedItemVersion());
        value.put("coin_return_minor", result.coinReturnMinor());
        value.put("wallet_balance_minor", result.walletBalanceMinor());
        value.put("wallet_state_version", result.walletStateVersion());
        value.put("player_state_version", result.playerStateVersion());
        value.put("commodity_returns", result.commodityReturns().stream().map(returnValue -> Map.of(
                "delivery_id", returnValue.deliveryId().toString(),
                "commodity_definition_id", returnValue.commodityDefinitionId(),
                "quantity", returnValue.quantity()
        )).toList());
        value.put("created_at", result.createdAt().toString());
        return value;
    }

    private static SalvageResult resultFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        Object rawReturns = value.get("commodity_returns");
        if (!(rawReturns instanceof List<?> list)) {
            throw new SalvageException("salvage commodity_returns is not a list");
        }
        List<SalvageCommodityReturn> returns = new ArrayList<>();
        for (Object rawReturn : list) {
            Map<String, Object> returned = objectMap(rawReturn, "commodity_return");
            returns.add(new SalvageCommodityReturn(
                    uuidValue(returned, "delivery_id"),
                    stringValue(returned, "commodity_definition_id"),
                    longValue(returned, "quantity")
            ));
        }
        returns.sort(Comparator.comparing(SalvageCommodityReturn::commodityDefinitionId));
        return new SalvageResult(
                uuidValue(value, "salvage_id"),
                uuidValue(value, "operation_id"),
                uuidValue(value, "player_id"),
                uuidValue(value, "item_instance_id"),
                stringValue(value, "item_definition_id"),
                longValue(value, "destroyed_item_version"),
                longValue(value, "coin_return_minor"),
                longValue(value, "wallet_balance_minor"),
                longValue(value, "wallet_state_version"),
                longValue(value, "player_state_version"),
                returns,
                Instant.parse(stringValue(value, "created_at"))
        );
    }

    private static Map<String, Object> requireType(ProcessedOperation operation, UUID operationId) {
        if (!OPERATION.equals(operation.operationType())) {
            throw new SalvageException(
                    "operation_id " + operationId + " already belongs to " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new SalvageException("Could not parse salvage idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SalvageException("Could not serialize salvage state", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new SalvageException("salvage field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new SalvageException("salvage result is missing field: " + field);
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
            throw new SalvageException("salvage field is not numeric: " + field);
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

    private static void requireNullableString(
            Map<String, Object> data,
            String field,
            String expected,
            UUID operationId
    ) {
        if (!Objects.equals(nullableString(data, field), expected)) {
            throw reused(operationId);
        }
    }

    private static void requireLong(Map<String, Object> data, String field, long expected, UUID operationId) {
        if (longValue(data, field) != expected) {
            throw reused(operationId);
        }
    }

    private static SalvageException reused(UUID operationId) {
        return new SalvageException("operation_id reused with a different salvage request: " + operationId);
    }

    private static long increment(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new SalvageException(target + " state_version overflow for " + id, exception);
        }
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new SalvageException(message, exception);
        }
    }

    private static UUID deterministicUuid(UUID operationId, String purpose, int ordinal) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:salvage:" + operationId + ":" + purpose + ":" + ordinal)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private record LiveSession(UUID playerId) {
        private LiveSession {
            playerId = Objects.requireNonNull(playerId, "playerId");
        }
    }

    private record LockedItem(
            UUID itemInstanceId,
            String definitionId,
            String locationKind,
            UUID locationId,
            long stateVersion
    ) {
    }

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Map.copyOf(Objects.requireNonNull(result, "result"));
        }
    }
}
