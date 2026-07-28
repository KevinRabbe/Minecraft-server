package io.github.kevinrabbe.minecraftserver.common.economy;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Returns OPEN-trade commodity/unique-item offers through durable pending delivery. */
public final class SecureTradeWithdrawalRepository {
    private static final String COMMODITY_WITHDRAW_OPERATION = "SECURE_TRADE_COMMODITY_WITHDRAW";
    private static final String UNIQUE_WITHDRAW_OPERATION = "SECURE_TRADE_UNIQUE_WITHDRAW";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public SecureTradeWithdrawalRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public SecureTradeWithdrawalResult withdrawCommodity(
            UUID operationId,
            UUID tradeId,
            UUID playerId,
            String commodityDefinitionId,
            long quantity,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerId, "playerId");
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        String commodity = commodityDefinitionId.trim();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(
                            processed.orElseThrow(), COMMODITY_WITHDRAW_OPERATION, operationId
                    );
                    requireUuid(data, "trade_id", tradeId, operationId);
                    requireUuid(data, "player_id", playerId, operationId);
                    requireString(data, "commodity_definition_id", commodity, operationId);
                    requireLong(data, "quantity", quantity, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    SecureTradeWithdrawalResult result = withdrawalFrom(data);
                    connection.commit();
                    return result;
                }

                SecureTradeSnapshot trade = readTrade(connection, tradeId, true);
                requireOpenParticipant(trade, playerId);
                long current = lockCommodityEscrow(connection, tradeId, playerId, commodity);
                if (quantity > current) {
                    throw new SecureTradeException("commodity withdrawal exceeds secure-trade escrow");
                }
                long remaining = current - quantity;
                if (remaining == 0) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            DELETE FROM secure_trade_commodity_escrow
                            WHERE trade_id = ? AND owner_player_id = ? AND commodity_definition_id = ?
                            """)) {
                        statement.setObject(1, tradeId);
                        statement.setObject(2, playerId);
                        statement.setString(3, commodity);
                        if (statement.executeUpdate() != 1) {
                            throw new SecureTradeException("commodity escrow changed during withdrawal");
                        }
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE secure_trade_commodity_escrow
                            SET quantity = ?
                            WHERE trade_id = ? AND owner_player_id = ? AND commodity_definition_id = ?
                            """)) {
                        statement.setLong(1, remaining);
                        statement.setObject(2, tradeId);
                        statement.setObject(3, playerId);
                        statement.setString(4, commodity);
                        if (statement.executeUpdate() != 1) {
                            throw new SecureTradeException("commodity escrow changed during withdrawal");
                        }
                    }
                }

                UUID deliveryId = deterministicUuid(operationId, "commodity-delivery");
                UUID sourceOperationId = deterministicUuid(operationId, "commodity-source");
                insertPendingCommodityDelivery(
                        connection, deliveryId, playerId, commodity, quantity, sourceOperationId
                );
                Instant createdAt = insertTradeCommodityDeliveryEvidence(
                        connection, tradeId, deliveryId, playerId, commodity, quantity
                );
                insertLedger(
                        connection,
                        operationId,
                        playerId,
                        "COMMODITY",
                        commodity,
                        quantity,
                        normalizedReason
                );
                SecureTradeSnapshot updatedTrade = advanceRevision(connection, trade);
                SecureTradeDeliverySnapshot delivery = new SecureTradeDeliverySnapshot(
                        tradeId,
                        deliveryId,
                        SecureTradeDeliveryKind.COMMODITY,
                        playerId,
                        playerId,
                        null,
                        commodity,
                        quantity,
                        createdAt
                );
                SecureTradeWithdrawalResult result = new SecureTradeWithdrawalResult(updatedTrade, delivery);
                LinkedHashMap<String, Object> data = requestMap(
                        tradeId, playerId, commodity, quantity, normalizedReason
                );
                data.put("trade", tradeMap(updatedTrade));
                data.put("delivery", deliveryMap(delivery));
                insertProcessed(connection, operationId, COMMODITY_WITHDRAW_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public SecureTradeWithdrawalResult withdrawUniqueItem(
            UUID operationId,
            UUID tradeId,
            UUID playerId,
            UUID itemInstanceId,
            long expectedEscrowItemVersion,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (expectedEscrowItemVersion < 0) {
            throw new IllegalArgumentException("expectedEscrowItemVersion must be >= 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(
                            processed.orElseThrow(), UNIQUE_WITHDRAW_OPERATION, operationId
                    );
                    requireUuid(data, "trade_id", tradeId, operationId);
                    requireUuid(data, "player_id", playerId, operationId);
                    requireUuid(data, "item_instance_id", itemInstanceId, operationId);
                    requireLong(data, "expected_escrow_item_version", expectedEscrowItemVersion, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    SecureTradeWithdrawalResult result = withdrawalFrom(data);
                    connection.commit();
                    return result;
                }

                SecureTradeSnapshot trade = readTrade(connection, tradeId, true);
                requireOpenParticipant(trade, playerId);
                UniqueEscrow escrow = lockUniqueEscrow(connection, tradeId, itemInstanceId);
                if (!escrow.ownerPlayerId().equals(playerId)) {
                    throw new SecureTradeException("only the original owner may withdraw a unique trade item");
                }
                if (escrow.escrowItemVersion() != expectedEscrowItemVersion) {
                    throw new SecureTradeException("stale secure-trade item escrow version");
                }
                LockedItem item = lockItem(connection, itemInstanceId);
                if (!"TRADE_ESCROW".equals(item.locationKind())
                        || !tradeId.equals(item.locationId())
                        || item.stateVersion() != expectedEscrowItemVersion) {
                    throw new SecureTradeException("unique item no longer matches secure-trade custody");
                }

                UUID deliveryId = deterministicUuid(operationId, "unique-delivery");
                UUID issueOperationId = deterministicUuid(operationId, "unique-issue");
                insertPendingUniqueDelivery(
                        connection, deliveryId, playerId, itemInstanceId, issueOperationId, normalizedReason
                );
                long nextItemVersion = increment(item.stateVersion(), "item", itemInstanceId);
                moveItemToPendingDelivery(
                        connection,
                        tradeId,
                        itemInstanceId,
                        item.stateVersion(),
                        nextItemVersion,
                        deliveryId
                );
                insertItemProvenance(
                        connection,
                        itemInstanceId,
                        nextItemVersion,
                        issueOperationId,
                        tradeId,
                        deliveryId,
                        normalizedReason
                );

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM secure_trade_unique_items
                        WHERE trade_id = ? AND item_instance_id = ? AND owner_player_id = ?
                        """)) {
                    statement.setObject(1, tradeId);
                    statement.setObject(2, itemInstanceId);
                    statement.setObject(3, playerId);
                    if (statement.executeUpdate() != 1) {
                        throw new SecureTradeException("unique-item trade escrow changed during withdrawal");
                    }
                }

                insertLedger(
                        connection,
                        operationId,
                        playerId,
                        "ITEM_INSTANCE",
                        itemInstanceId.toString(),
                        1,
                        normalizedReason
                );
                Instant createdAt = insertTradeUniqueDeliveryEvidence(
                        connection, tradeId, deliveryId, playerId, itemInstanceId
                );
                SecureTradeSnapshot updatedTrade = advanceRevision(connection, trade);
                SecureTradeDeliverySnapshot delivery = new SecureTradeDeliverySnapshot(
                        tradeId,
                        deliveryId,
                        SecureTradeDeliveryKind.UNIQUE_ITEM,
                        playerId,
                        playerId,
                        itemInstanceId,
                        null,
                        null,
                        createdAt
                );
                SecureTradeWithdrawalResult result = new SecureTradeWithdrawalResult(updatedTrade, delivery);
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("trade_id", tradeId.toString());
                data.put("player_id", playerId.toString());
                data.put("item_instance_id", itemInstanceId.toString());
                data.put("expected_escrow_item_version", expectedEscrowItemVersion);
                data.put("reason", normalizedReason);
                data.put("trade", tradeMap(updatedTrade));
                data.put("delivery", deliveryMap(delivery));
                insertProcessed(connection, operationId, UNIQUE_WITHDRAW_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static long lockCommodityEscrow(
            Connection connection,
            UUID tradeId,
            UUID playerId,
            String commodity
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT quantity
                FROM secure_trade_commodity_escrow
                WHERE trade_id = ? AND owner_player_id = ? AND commodity_definition_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, playerId);
            statement.setString(3, commodity);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("commodity is not present in secure-trade escrow");
                }
                return row.getLong("quantity");
            }
        }
    }

    private static UniqueEscrow lockUniqueEscrow(Connection connection, UUID tradeId, UUID itemInstanceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_player_id, escrow_item_version
                FROM secure_trade_unique_items
                WHERE trade_id = ? AND item_instance_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("unique item is not present in secure-trade escrow");
                }
                return new UniqueEscrow(
                        row.getObject("owner_player_id", UUID.class),
                        row.getLong("escrow_item_version")
                );
            }
        }
    }

    private static LockedItem lockItem(Connection connection, UUID itemInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT location_kind, location_id, state_version
                FROM item_instances
                WHERE item_instance_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("Unknown secure-trade item: " + itemInstanceId);
                }
                return new LockedItem(
                        row.getString("location_kind"),
                        row.getObject("location_id", UUID.class),
                        row.getLong("state_version")
                );
            }
        }
    }

    private static void insertPendingCommodityDelivery(
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

    private static void insertPendingUniqueDelivery(
            Connection connection,
            UUID deliveryId,
            UUID playerId,
            UUID itemInstanceId,
            UUID issueOperationId,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_unique_deliveries(
                    delivery_id, recipient_player_id, item_instance_id, status, issue_operation_id, issue_reason
                ) VALUES (?, ?, ?, 'PENDING', ?, ?)
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, playerId);
            statement.setObject(3, itemInstanceId);
            statement.setObject(4, issueOperationId);
            statement.setString(5, reason);
            statement.executeUpdate();
        }
    }

    private static void moveItemToPendingDelivery(
            Connection connection,
            UUID tradeId,
            UUID itemInstanceId,
            long expectedVersion,
            long nextVersion,
            UUID deliveryId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = 'PENDING_DELIVERY',
                    location_id = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ?
                  AND location_kind = 'TRADE_ESCROW'
                  AND location_id = ?
                  AND state_version = ?
                """)) {
            statement.setObject(1, deliveryId);
            statement.setLong(2, nextVersion);
            statement.setObject(3, itemInstanceId);
            statement.setObject(4, tradeId);
            statement.setLong(5, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new SecureTradeException("unique-item custody changed during withdrawal");
            }
        }
    }

    private static void insertItemProvenance(
            Connection connection,
            UUID itemInstanceId,
            long sequenceNo,
            UUID issueOperationId,
            UUID tradeId,
            UUID deliveryId,
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
                ) VALUES (?, ?, ?, 'MOVED', 'TRADE_ESCROW', ?, 'PENDING_DELIVERY', ?, ?, NULL)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, sequenceNo);
            statement.setObject(3, issueOperationId);
            statement.setObject(4, tradeId);
            statement.setObject(5, deliveryId);
            statement.setString(6, reason);
            statement.executeUpdate();
        }
    }

    private static Instant insertTradeCommodityDeliveryEvidence(
            Connection connection,
            UUID tradeId,
            UUID deliveryId,
            UUID playerId,
            String commodity,
            long quantity
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO secure_trade_deliveries(
                    trade_id, delivery_id, delivery_kind, source_owner_player_id, recipient_player_id,
                    commodity_definition_id, quantity
                ) VALUES (?, ?, 'COMMODITY', ?, ?, ?, ?)
                RETURNING created_at
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, deliveryId);
            statement.setObject(3, playerId);
            statement.setObject(4, playerId);
            statement.setString(5, commodity);
            statement.setLong(6, quantity);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getTimestamp("created_at").toInstant();
            }
        }
    }

    private static Instant insertTradeUniqueDeliveryEvidence(
            Connection connection,
            UUID tradeId,
            UUID deliveryId,
            UUID playerId,
            UUID itemInstanceId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO secure_trade_deliveries(
                    trade_id, delivery_id, delivery_kind, source_owner_player_id, recipient_player_id, item_instance_id
                ) VALUES (?, ?, 'UNIQUE_ITEM', ?, ?, ?)
                RETURNING created_at
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, deliveryId);
            statement.setObject(3, playerId);
            statement.setObject(4, playerId);
            statement.setObject(5, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getTimestamp("created_at").toInstant();
            }
        }
    }

    private static void insertLedger(
            Connection connection,
            UUID operationId,
            UUID playerId,
            String assetType,
            String assetId,
            long amount,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                ) VALUES (?, 0, ?, ?, ?, ?, 'CREDIT', ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, playerId);
            statement.setString(3, assetType);
            statement.setString(4, assetId);
            statement.setLong(5, amount);
            statement.setString(6, reason);
            statement.executeUpdate();
        }
    }

    private static SecureTradeSnapshot advanceRevision(Connection connection, SecureTradeSnapshot current)
            throws SQLException {
        long nextRevision = increment(current.revision(), "trade revision", current.tradeId());
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
                throw new SecureTradeException("secure trade changed concurrently during withdrawal");
            }
        }
        return readTrade(connection, current.tradeId(), false);
    }

    private static SecureTradeSnapshot readTrade(Connection connection, UUID tradeId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT player_a_id, player_b_id, status, revision,
                       player_a_confirmed_revision, player_b_confirmed_revision,
                       created_at, updated_at, settled_at
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

    private static void requireOpenParticipant(SecureTradeSnapshot trade, UUID playerId) {
        if (trade.status() != SecureTradeStatus.OPEN) {
            throw new SecureTradeException("secure-trade offers may be withdrawn only while OPEN");
        }
        if (!trade.participant(playerId)) {
            throw new SecureTradeException("player is not a secure-trade participant: " + playerId);
        }
    }

    private static UUID deterministicUuid(UUID operationId, String purpose) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:secure-trade-withdraw:" + operationId + ":" + purpose)
                        .getBytes(StandardCharsets.UTF_8)
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

    private static LinkedHashMap<String, Object> requestMap(
            UUID tradeId,
            UUID playerId,
            String commodity,
            long quantity,
            String reason
    ) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("trade_id", tradeId.toString());
        data.put("player_id", playerId.toString());
        data.put("commodity_definition_id", commodity);
        data.put("quantity", quantity);
        data.put("reason", reason);
        return data;
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

    private static Map<String, Object> deliveryMap(SecureTradeDeliverySnapshot delivery) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("trade_id", delivery.tradeId().toString());
        value.put("delivery_id", delivery.deliveryId().toString());
        value.put("kind", delivery.kind().name());
        value.put("source_owner_player_id", delivery.sourceOwnerPlayerId().toString());
        value.put("recipient_player_id", delivery.recipientPlayerId().toString());
        value.put("item_instance_id", delivery.itemInstanceId() == null ? null : delivery.itemInstanceId().toString());
        value.put("commodity_definition_id", delivery.commodityDefinitionId());
        value.put("quantity", delivery.quantity());
        value.put("created_at", delivery.createdAt().toString());
        return value;
    }

    private static SecureTradeWithdrawalResult withdrawalFrom(Map<String, Object> data) {
        return new SecureTradeWithdrawalResult(
                tradeFrom(data.get("trade")),
                deliveryFrom(data.get("delivery"))
        );
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

    private static SecureTradeDeliverySnapshot deliveryFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "delivery");
        String itemId = nullableString(value, "item_instance_id");
        return new SecureTradeDeliverySnapshot(
                uuidValue(value, "trade_id"),
                uuidValue(value, "delivery_id"),
                SecureTradeDeliveryKind.valueOf(stringValue(value, "kind")),
                uuidValue(value, "source_owner_player_id"),
                uuidValue(value, "recipient_player_id"),
                itemId == null ? null : UUID.fromString(itemId),
                nullableString(value, "commodity_definition_id"),
                nullableLong(value, "quantity"),
                Instant.parse(stringValue(value, "created_at"))
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new SecureTradeException("Could not parse secure-trade withdrawal idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SecureTradeException("Could not serialize secure-trade withdrawal idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new SecureTradeException("secure-trade withdrawal field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new SecureTradeException("secure-trade withdrawal result is missing field: " + field);
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
            throw new SecureTradeException("secure-trade withdrawal field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static Long nullableLong(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new SecureTradeException("secure-trade withdrawal field is not numeric: " + field);
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
            throw new SecureTradeException("operation_id reused with a different secure-trade withdrawal: " + operationId);
        }
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) {
            throw new SecureTradeException("operation_id reused with a different secure-trade withdrawal: " + operationId);
        }
    }

    private static void requireLong(Map<String, Object> data, String field, long expected, UUID operationId) {
        if (longValue(data, field) != expected) {
            throw new SecureTradeException("operation_id reused with a different secure-trade withdrawal: " + operationId);
        }
    }

    private static long increment(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new SecureTradeException(target + " overflow for " + id, exception);
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

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record UniqueEscrow(UUID ownerPlayerId, long escrowItemVersion) {
    }

    private record LockedItem(String locationKind, UUID locationId, long stateVersion) {
    }

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Map.copyOf(Objects.requireNonNull(result, "result"));
        }
    }
}
