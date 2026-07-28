package io.github.kevinrabbe.minecraftserver.common.economy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Secure-trade escrow entry for commodities and individualized items. */
public final class SecureTradeAssetRepository {
    private static final String COMMODITY_ADD_OPERATION = "SECURE_TRADE_COMMODITY_ADD";
    private static final String UNIQUE_ITEM_ADD_OPERATION = "SECURE_TRADE_UNIQUE_ITEM_ADD";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final CommodityEscrowValidator commodityValidator;
    private final UniqueItemEscrowValidator uniqueItemValidator;
    private final PlayerStateRepository playerStates;

    public SecureTradeAssetRepository(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            CommodityEscrowValidator commodityValidator,
            UniqueItemEscrowValidator uniqueItemValidator
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.commodityValidator = Objects.requireNonNull(commodityValidator, "commodityValidator");
        this.uniqueItemValidator = Objects.requireNonNull(uniqueItemValidator, "uniqueItemValidator");
        this.playerStates = new PlayerStateRepository(dataSource);
    }

    public SecureTradeCommodityOfferResult addCommodity(
            UUID operationId,
            UUID tradeId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            String commodityDefinitionId,
            long quantity,
            String logicalZoneId,
            String entryPoint,
            byte[] nextPlayerStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(nextPlayerStatePayload, "nextPlayerStatePayload");
        if (expectedPlayerStateVersion < 0) {
            throw new IllegalArgumentException("expectedPlayerStateVersion must be >= 0");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        String backend = requireNonBlank(backendId, "backendId");
        String commodity = requireCommodity(commodityDefinitionId).definitionId();
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
                    Map<String, Object> data = requireType(processed.orElseThrow(), COMMODITY_ADD_OPERATION, operationId);
                    requireUuid(data, "trade_id", tradeId, operationId);
                    requireUuid(data, "session_id", sessionId, operationId);
                    requireString(data, "backend_id", backend, operationId);
                    requireLong(data, "expected_player_state_version", expectedPlayerStateVersion, operationId);
                    requireString(data, "commodity_definition_id", commodity, operationId);
                    requireLong(data, "quantity", quantity, operationId);
                    requireNullableString(data, "logical_zone_id", zone, operationId);
                    requireNullableString(data, "entry_point", entry, operationId);
                    requireString(data, "payload_sha256", payloadHash, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    SecureTradeCommodityOfferResult result = commodityResultFrom(data);
                    connection.commit();
                    return result;
                }

                SecureTradeSnapshot trade = readTrade(connection, tradeId, true);
                requireOpen(trade);
                UUID playerId = playerIdForSession(connection, sessionId);
                requireParticipant(trade, playerId);
                long currentQuantity = readCommodityEscrow(connection, tradeId, playerId, commodity);
                long nextQuantity = addExact(currentQuantity, quantity, "secure-trade commodity escrow overflow");

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
                                throw new SecureTradeException("session player changed during commodity escrow");
                            }
                            commodityValidator.verifyRemoval(
                                    lockedPlayerId,
                                    commodity,
                                    quantity,
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                upsertCommodityEscrow(connection, tradeId, playerId, commodity, nextQuantity);
                insertAssetLedger(
                        connection,
                        operationId,
                        playerId,
                        "COMMODITY",
                        commodity,
                        quantity,
                        "DEBIT",
                        normalizedReason
                );
                SecureTradeSnapshot updatedTrade = advanceRevision(connection, trade);
                SecureTradeCommodityOfferResult result = new SecureTradeCommodityOfferResult(
                        updatedTrade,
                        playerId,
                        commodity,
                        nextQuantity,
                        nextPlayerStateVersion
                );

                LinkedHashMap<String, Object> data = commonRequest(
                        tradeId,
                        sessionId,
                        backend,
                        expectedPlayerStateVersion,
                        zone,
                        entry,
                        payloadHash,
                        normalizedReason
                );
                data.put("commodity_definition_id", commodity);
                data.put("quantity", quantity);
                data.put("player_id", playerId.toString());
                data.put("escrow_quantity", nextQuantity);
                data.put("player_state_version", nextPlayerStateVersion);
                data.put("trade", tradeMap(updatedTrade));
                insertProcessed(connection, operationId, COMMODITY_ADD_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public SecureTradeUniqueItemOfferResult addUniqueItem(
            UUID operationId,
            UUID tradeId,
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
        Objects.requireNonNull(tradeId, "tradeId");
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
                    Map<String, Object> data = requireType(processed.orElseThrow(), UNIQUE_ITEM_ADD_OPERATION, operationId);
                    requireUuid(data, "trade_id", tradeId, operationId);
                    requireUuid(data, "session_id", sessionId, operationId);
                    requireString(data, "backend_id", backend, operationId);
                    requireLong(data, "expected_player_state_version", expectedPlayerStateVersion, operationId);
                    requireUuid(data, "item_instance_id", itemInstanceId, operationId);
                    requireLong(data, "expected_item_state_version", expectedItemStateVersion, operationId);
                    requireNullableString(data, "logical_zone_id", zone, operationId);
                    requireNullableString(data, "entry_point", entry, operationId);
                    requireString(data, "payload_sha256", payloadHash, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    SecureTradeUniqueItemOfferResult result = uniqueResultFrom(data);
                    connection.commit();
                    return result;
                }

                SecureTradeSnapshot trade = readTrade(connection, tradeId, true);
                requireOpen(trade);
                UUID playerId = playerIdForSession(connection, sessionId);
                requireParticipant(trade, playerId);
                LockedItem item = lockItem(connection, itemInstanceId);
                requireIndividual(item.definitionId());
                if (item.stateVersion() != expectedItemStateVersion) {
                    throw new SecureTradeException("stale unique-item state_version for trade escrow: " + itemInstanceId);
                }
                if (!"PLAYER_INVENTORY".equals(item.locationKind()) || !playerId.equals(item.locationId())) {
                    throw new SecureTradeException("player does not own authoritative unique-item inventory custody");
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
                                throw new SecureTradeException("session player changed during unique-item escrow");
                            }
                            uniqueItemValidator.verifyRemoval(
                                    lockedPlayerId,
                                    itemInstanceId,
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                long escrowItemVersion = incrementVersion(item.stateVersion(), "item", itemInstanceId);
                moveItemToTradeEscrow(
                        connection,
                        itemInstanceId,
                        item.stateVersion(),
                        escrowItemVersion,
                        tradeId
                );
                insertItemProvenance(
                        connection,
                        itemInstanceId,
                        escrowItemVersion,
                        operationId,
                        playerId,
                        tradeId,
                        normalizedReason
                );
                insertAssetLedger(
                        connection,
                        operationId,
                        playerId,
                        "ITEM_INSTANCE",
                        itemInstanceId.toString(),
                        1,
                        "DEBIT",
                        normalizedReason
                );
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO secure_trade_unique_items(
                            trade_id, owner_player_id, item_instance_id, escrow_item_version
                        ) VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, tradeId);
                    statement.setObject(2, playerId);
                    statement.setObject(3, itemInstanceId);
                    statement.setLong(4, escrowItemVersion);
                    statement.executeUpdate();
                }

                SecureTradeSnapshot updatedTrade = advanceRevision(connection, trade);
                SecureTradeUniqueItemOfferResult result = new SecureTradeUniqueItemOfferResult(
                        updatedTrade,
                        playerId,
                        itemInstanceId,
                        escrowItemVersion,
                        nextPlayerStateVersion
                );

                LinkedHashMap<String, Object> data = commonRequest(
                        tradeId,
                        sessionId,
                        backend,
                        expectedPlayerStateVersion,
                        zone,
                        entry,
                        payloadHash,
                        normalizedReason
                );
                data.put("item_instance_id", itemInstanceId.toString());
                data.put("expected_item_state_version", expectedItemStateVersion);
                data.put("player_id", playerId.toString());
                data.put("escrow_item_version", escrowItemVersion);
                data.put("player_state_version", nextPlayerStateVersion);
                data.put("trade", tradeMap(updatedTrade));
                insertProcessed(connection, operationId, UNIQUE_ITEM_ADD_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public long commodityEscrow(UUID tradeId, UUID playerId, String commodityDefinitionId) throws SQLException {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerId, "playerId");
        String commodity = requireCommodity(commodityDefinitionId).definitionId();
        try (Connection connection = dataSource.getConnection()) {
            SecureTradeSnapshot trade = readTrade(connection, tradeId, false);
            requireParticipant(trade, playerId);
            return readCommodityEscrow(connection, tradeId, playerId, commodity);
        }
    }

    private ItemDefinition requireCommodity(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
            throw new SecureTradeException("secure-trade commodity escrow requires COMMODITY definition: " + definitionId);
        }
        return definition;
    }

    private ItemDefinition requireIndividual(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new SecureTradeException("secure-trade unique escrow requires INDIVIDUAL definition: " + definitionId);
        }
        return definition;
    }

    private static SecureTradeSnapshot advanceRevision(Connection connection, SecureTradeSnapshot current)
            throws SQLException {
        long nextRevision = incrementVersion(current.revision(), "trade revision", current.tradeId());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE secure_trades
                SET revision = ?,
                    player_a_confirmed_revision = NULL,
                    player_b_confirmed_revision = NULL,
                    updated_at = NOW()
                WHERE trade_id = ? AND status = 'OPEN' AND revision = ?
                """)) {
            statement.setLong(1, nextRevision);
            statement.setObject(2, current.tradeId());
            statement.setLong(3, current.revision());
            if (statement.executeUpdate() != 1) {
                throw new SecureTradeException("secure trade changed concurrently while advancing revision");
            }
        }
        return readTrade(connection, current.tradeId(), false);
    }

    private static SecureTradeSnapshot readTrade(Connection connection, UUID tradeId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT player_a_id,
                       player_b_id,
                       status,
                       revision,
                       player_a_confirmed_revision,
                       player_b_confirmed_revision,
                       created_at,
                       updated_at,
                       settled_at
                FROM secure_trades
                WHERE trade_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tradeId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("Unknown secure trade: " + tradeId);
                }
                Timestamp settled = row.getTimestamp("settled_at");
                return new SecureTradeSnapshot(
                        tradeId,
                        row.getObject("player_a_id", UUID.class),
                        row.getObject("player_b_id", UUID.class),
                        SecureTradeStatus.valueOf(row.getString("status")),
                        row.getLong("revision"),
                        row.getObject("player_a_confirmed_revision", Long.class),
                        row.getObject("player_b_confirmed_revision", Long.class),
                        row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant(),
                        settled == null ? null : settled.toInstant()
                );
            }
        }
    }

    private static void requireOpen(SecureTradeSnapshot trade) {
        if (trade.status() != SecureTradeStatus.OPEN) {
            throw new SecureTradeException("secure-trade offers may change only while OPEN: " + trade.tradeId());
        }
    }

    private static void requireParticipant(SecureTradeSnapshot trade, UUID playerId) {
        if (!trade.participant(playerId)) {
            throw new SecureTradeException("player is not a secure-trade participant: " + playerId);
        }
    }

    private static UUID playerIdForSession(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id
                FROM player_sessions
                WHERE network_session_id = ?
                """)) {
            statement.setObject(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("Unknown player session: " + sessionId);
                }
                return row.getObject("player_id", UUID.class);
            }
        }
    }

    private static long readCommodityEscrow(
            Connection connection,
            UUID tradeId,
            UUID playerId,
            String commodity
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT quantity
                FROM secure_trade_commodity_escrow
                WHERE trade_id = ? AND owner_player_id = ? AND commodity_definition_id = ?
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, playerId);
            statement.setString(3, commodity);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong("quantity") : 0L;
            }
        }
    }

    private static void upsertCommodityEscrow(
            Connection connection,
            UUID tradeId,
            UUID playerId,
            String commodity,
            long quantity
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO secure_trade_commodity_escrow(
                    trade_id, owner_player_id, commodity_definition_id, quantity
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (trade_id, owner_player_id, commodity_definition_id)
                DO UPDATE SET quantity = EXCLUDED.quantity
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, playerId);
            statement.setString(3, commodity);
            statement.setLong(4, quantity);
            statement.executeUpdate();
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
                    throw new SecureTradeException("Unknown unique item: " + itemInstanceId);
                }
                return new LockedItem(
                        row.getString("definition_id"),
                        row.getString("location_kind"),
                        row.getObject("location_id", UUID.class),
                        row.getLong("state_version")
                );
            }
        }
    }

    private static void moveItemToTradeEscrow(
            Connection connection,
            UUID itemInstanceId,
            long expectedVersion,
            long nextVersion,
            UUID tradeId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = 'TRADE_ESCROW',
                    location_id = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ? AND state_version = ? AND location_kind = 'PLAYER_INVENTORY'
                """)) {
            statement.setObject(1, tradeId);
            statement.setLong(2, nextVersion);
            statement.setObject(3, itemInstanceId);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new SecureTradeException("unique-item authority changed concurrently: " + itemInstanceId);
            }
        }
    }

    private static void insertItemProvenance(
            Connection connection,
            UUID itemInstanceId,
            long sequenceNo,
            UUID operationId,
            UUID playerId,
            UUID tradeId,
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
                ) VALUES (?, ?, ?, 'MOVED', 'PLAYER_INVENTORY', ?, 'TRADE_ESCROW', ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, sequenceNo);
            statement.setObject(3, operationId);
            statement.setObject(4, playerId);
            statement.setObject(5, tradeId);
            statement.setString(6, reason);
            statement.setObject(7, playerId);
            statement.executeUpdate();
        }
    }

    private static void insertAssetLedger(
            Connection connection,
            UUID operationId,
            UUID playerId,
            String assetType,
            String assetId,
            long amount,
            String direction,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                ) VALUES (?, 0, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, playerId);
            statement.setString(3, assetType);
            statement.setString(4, assetId);
            statement.setLong(5, amount);
            statement.setString(6, direction);
            statement.setString(7, reason);
            statement.executeUpdate();
        }
    }

    private static LinkedHashMap<String, Object> commonRequest(
            UUID tradeId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            String logicalZoneId,
            String entryPoint,
            String payloadHash,
            String reason
    ) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("trade_id", tradeId.toString());
        data.put("session_id", sessionId.toString());
        data.put("backend_id", backendId);
        data.put("expected_player_state_version", expectedPlayerStateVersion);
        data.put("logical_zone_id", logicalZoneId);
        data.put("entry_point", entryPoint);
        data.put("payload_sha256", payloadHash);
        data.put("reason", reason);
        return data;
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

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            String operationType,
            Map<String, Object> result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, writeJson(result));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requireType(
            ProcessedOperation operation,
            String expectedType,
            UUID operationId
    ) {
        if (!expectedType.equals(operation.operationType())) {
            throw new SecureTradeException(
                    "operation_id " + operationId + " already belongs to operation type " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static Map<String, Object> tradeMap(SecureTradeSnapshot trade) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("trade_id", trade.tradeId().toString());
        value.put("player_a_id", trade.playerAId().toString());
        value.put("player_b_id", trade.playerBId().toString());
        value.put("status", trade.status().name());
        value.put("revision", trade.revision());
        value.put("player_a_confirmed_revision", trade.playerAConfirmedRevision());
        value.put("player_b_confirmed_revision", trade.playerBConfirmedRevision());
        value.put("created_at", trade.createdAt().toString());
        value.put("updated_at", trade.updatedAt().toString());
        value.put("settled_at", trade.settledAt() == null ? null : trade.settledAt().toString());
        return value;
    }

    private static SecureTradeSnapshot tradeFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "trade");
        return new SecureTradeSnapshot(
                uuidValue(value, "trade_id"),
                uuidValue(value, "player_a_id"),
                uuidValue(value, "player_b_id"),
                SecureTradeStatus.valueOf(stringValue(value, "status")),
                longValue(value, "revision"),
                nullableLong(value, "player_a_confirmed_revision"),
                nullableLong(value, "player_b_confirmed_revision"),
                Instant.parse(stringValue(value, "created_at")),
                Instant.parse(stringValue(value, "updated_at")),
                nullableInstant(value, "settled_at")
        );
    }

    private static SecureTradeCommodityOfferResult commodityResultFrom(Map<String, Object> data) {
        return new SecureTradeCommodityOfferResult(
                tradeFrom(data.get("trade")),
                uuidValue(data, "player_id"),
                stringValue(data, "commodity_definition_id"),
                longValue(data, "escrow_quantity"),
                longValue(data, "player_state_version")
        );
    }

    private static SecureTradeUniqueItemOfferResult uniqueResultFrom(Map<String, Object> data) {
        return new SecureTradeUniqueItemOfferResult(
                tradeFrom(data.get("trade")),
                uuidValue(data, "player_id"),
                uuidValue(data, "item_instance_id"),
                longValue(data, "escrow_item_version"),
                longValue(data, "player_state_version")
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new SecureTradeException("Could not parse secure-trade asset idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SecureTradeException("Could not serialize secure-trade asset idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new SecureTradeException("secure-trade idempotency field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new SecureTradeException("secure-trade idempotency result is missing field: " + field);
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
            throw new SecureTradeException("secure-trade idempotency field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static Long nullableLong(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new SecureTradeException("secure-trade idempotency field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static Instant nullableInstant(Map<String, Object> value, String field) {
        String raw = nullableString(value, field);
        return raw == null ? null : Instant.parse(raw);
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!uuidValue(data, field).equals(expected)) {
            throw new SecureTradeException("operation_id reused with a different secure-trade request: " + operationId);
        }
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) {
            throw new SecureTradeException("operation_id reused with a different secure-trade request: " + operationId);
        }
    }

    private static void requireNullableString(
            Map<String, Object> data,
            String field,
            String expected,
            UUID operationId
    ) {
        if (!Objects.equals(nullableString(data, field), expected)) {
            throw new SecureTradeException("operation_id reused with a different secure-trade request: " + operationId);
        }
    }

    private static void requireLong(Map<String, Object> data, String field, long expected, UUID operationId) {
        if (longValue(data, field) != expected) {
            throw new SecureTradeException("operation_id reused with a different secure-trade request: " + operationId);
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new SecureTradeException(message, exception);
        }
    }

    private static long incrementVersion(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new SecureTradeException(target + " state_version overflow for " + id, exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedItem(String definitionId, String locationKind, UUID locationId, long stateVersion) {
    }

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Map.copyOf(Objects.requireNonNull(result, "result"));
        }
    }
}
