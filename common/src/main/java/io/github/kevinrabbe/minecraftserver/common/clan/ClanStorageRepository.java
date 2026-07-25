package io.github.kevinrabbe.minecraftserver.common.clan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityEscrowValidator;
import io.github.kevinrabbe.minecraftserver.common.economy.UniqueItemEscrowValidator;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
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
 * Authoritative shared clan storage for fungible commodities and individualized items.
 *
 * <p>Deposits prove the exact serialized player-state removal in the same transaction that creates clan custody.
 * Withdrawals never write directly into a live inventory: value moves first into the existing durable pending-delivery
 * authorities. This keeps clan storage independent from Paper inventory timing and preserves crash recovery.</p>
 */
public final class ClanStorageRepository {
    private static final String COMMODITY_DEPOSIT_OPERATION = "CLAN_STORAGE_COMMODITY_DEPOSIT";
    private static final String COMMODITY_WITHDRAW_OPERATION = "CLAN_STORAGE_COMMODITY_WITHDRAW";
    private static final String UNIQUE_DEPOSIT_OPERATION = "CLAN_STORAGE_UNIQUE_DEPOSIT";
    private static final String UNIQUE_WITHDRAW_OPERATION = "CLAN_STORAGE_UNIQUE_WITHDRAW";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final CommodityEscrowValidator commodityValidator;
    private final UniqueItemEscrowValidator uniqueItemValidator;
    private final PlayerStateRepository playerStates;

    public ClanStorageRepository(
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

    public Optional<ClanCommodityStorageSnapshot> loadCommodity(UUID clanId, String commodityDefinitionId)
            throws SQLException {
        Objects.requireNonNull(clanId, "clanId");
        String commodity = requireCommodity(commodityDefinitionId).definitionId();
        try (Connection connection = dataSource.getConnection()) {
            requireClan(connection, clanId, false);
            return readCommodity(connection, clanId, commodity, false);
        }
    }

    public List<ClanUniqueStorageItemSnapshot> listUniqueItems(UUID clanId, int limit) throws SQLException {
        Objects.requireNonNull(clanId, "clanId");
        if (limit <= 0 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        try (Connection connection = dataSource.getConnection()) {
            requireClan(connection, clanId, false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT item_instance_id, definition_id, state_version, updated_at
                    FROM item_instances
                    WHERE location_kind = 'CLAN_STORAGE' AND location_id = ?
                    ORDER BY updated_at ASC, item_instance_id ASC
                    LIMIT ?
                    """)) {
                statement.setObject(1, clanId);
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<ClanUniqueStorageItemSnapshot> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(new ClanUniqueStorageItemSnapshot(
                                clanId,
                                rows.getObject("item_instance_id", UUID.class),
                                rows.getString("definition_id"),
                                rows.getLong("state_version"),
                                rows.getTimestamp("updated_at").toInstant()
                        ));
                    }
                    return List.copyOf(result);
                }
            }
        }
    }

    public ClanCommodityStorageDepositResult depositCommodity(
            UUID operationId,
            UUID clanId,
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
        Objects.requireNonNull(clanId, "clanId");
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
        Map<String, Object> request = requestMap(
                "clan_id", clanId,
                "session_id", sessionId,
                "backend_id", backend,
                "expected_player_state_version", expectedPlayerStateVersion,
                "commodity_definition_id", commodity,
                "quantity", quantity,
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
                    Object result = requireReplay(processed.orElseThrow(), COMMODITY_DEPOSIT_OPERATION, request, operationId);
                    ClanCommodityStorageDepositResult replay = commodityDepositFrom(result);
                    connection.commit();
                    return replay;
                }

                UUID playerId = playerIdForSession(connection, sessionId);
                ClanRole role = lockClanMember(connection, clanId, playerId);
                requirePermission(role, ClanAssetPermission.STORAGE_DEPOSIT);
                ensureCommodityRow(connection, clanId, commodity);
                ClanCommodityStorageSnapshot current = readCommodity(connection, clanId, commodity, true).orElseThrow();
                long nextQuantity = addExact(current.quantity(), quantity, "clan commodity quantity overflow");
                long nextStorageVersion = increment(current.stateVersion(), "clan commodity storage", clanId);

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
                                throw new ClanAssetException("session player changed during clan commodity deposit");
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

                ClanCommodityStorageSnapshot updated = updateCommodity(
                        connection,
                        current,
                        nextQuantity,
                        nextStorageVersion
                );
                insertLedger(connection, operationId, 0, playerId, "COMMODITY", commodity, quantity,
                        "DEBIT", normalizedReason, clanId);
                insertLedger(connection, operationId, 1, null, "COMMODITY", commodity, quantity,
                        "CREDIT", normalizedReason, clanId);

                ClanCommodityStorageDepositResult result = new ClanCommodityStorageDepositResult(
                        updated,
                        playerId,
                        quantity,
                        nextPlayerStateVersion
                );
                insertProcessed(connection, operationId, COMMODITY_DEPOSIT_OPERATION, request, commodityDepositMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanCommodityStorageWithdrawResult withdrawCommodity(
            UUID operationId,
            UUID clanId,
            UUID playerId,
            String commodityDefinitionId,
            long quantity,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(playerId, "playerId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        String commodity = requireCommodity(commodityDefinitionId).definitionId();
        String normalizedReason = requireReason(reason);
        Map<String, Object> request = requestMap(
                "clan_id", clanId,
                "player_id", playerId,
                "commodity_definition_id", commodity,
                "quantity", quantity,
                "reason", normalizedReason
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Object result = requireReplay(processed.orElseThrow(), COMMODITY_WITHDRAW_OPERATION, request, operationId);
                    ClanCommodityStorageWithdrawResult replay = commodityWithdrawFrom(result);
                    connection.commit();
                    return replay;
                }

                ClanRole role = lockClanMember(connection, clanId, playerId);
                requirePermission(role, ClanAssetPermission.STORAGE_WITHDRAW);
                ClanCommodityStorageSnapshot current = readCommodity(connection, clanId, commodity, true)
                        .orElseThrow(() -> new ClanAssetException("clan commodity is not stored: " + commodity));
                if (current.quantity() < quantity) {
                    throw new ClanAssetException("insufficient clan commodity quantity: " + commodity);
                }
                long nextStorageVersion = increment(current.stateVersion(), "clan commodity storage", clanId);
                ClanCommodityStorageSnapshot updated = updateCommodity(
                        connection,
                        current,
                        current.quantity() - quantity,
                        nextStorageVersion
                );
                UUID deliveryId = deterministicUuid(operationId, "clan-commodity-delivery");
                insertCommodityDelivery(connection, deliveryId, playerId, commodity, quantity, operationId);
                insertLedger(connection, operationId, 0, null, "COMMODITY", commodity, quantity,
                        "DEBIT", normalizedReason, clanId);
                insertLedger(connection, operationId, 1, playerId, "COMMODITY", commodity, quantity,
                        "CREDIT", normalizedReason, clanId);

                ClanCommodityStorageWithdrawResult result = new ClanCommodityStorageWithdrawResult(
                        updated,
                        playerId,
                        quantity,
                        deliveryId
                );
                insertProcessed(connection, operationId, COMMODITY_WITHDRAW_OPERATION, request, commodityWithdrawMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanUniqueStorageDepositResult depositUniqueItem(
            UUID operationId,
            UUID clanId,
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
        Objects.requireNonNull(clanId, "clanId");
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
                "clan_id", clanId,
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
                    Object result = requireReplay(processed.orElseThrow(), UNIQUE_DEPOSIT_OPERATION, request, operationId);
                    ClanUniqueStorageDepositResult replay = uniqueDepositFrom(result);
                    connection.commit();
                    return replay;
                }

                UUID playerId = playerIdForSession(connection, sessionId);
                ClanRole role = lockClanMember(connection, clanId, playerId);
                requirePermission(role, ClanAssetPermission.STORAGE_DEPOSIT);
                LockedItem item = lockItem(connection, itemInstanceId);
                requireIndividual(item.definitionId());
                if (item.stateVersion() != expectedItemStateVersion) {
                    throw new ClanAssetException("stale unique-item state_version for clan deposit: " + itemInstanceId);
                }
                if (!"PLAYER_INVENTORY".equals(item.locationKind()) || !playerId.equals(item.locationId())) {
                    throw new ClanAssetException("player does not own authoritative unique-item inventory custody");
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
                                throw new ClanAssetException("session player changed during clan unique-item deposit");
                            }
                            uniqueItemValidator.verifyRemoval(
                                    lockedPlayerId,
                                    itemInstanceId,
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                long nextItemVersion = increment(item.stateVersion(), "unique item", itemInstanceId);
                moveItem(
                        connection,
                        itemInstanceId,
                        item.stateVersion(),
                        "PLAYER_INVENTORY",
                        playerId,
                        "CLAN_STORAGE",
                        clanId,
                        nextItemVersion
                );
                insertProvenance(
                        connection,
                        itemInstanceId,
                        nextItemVersion,
                        operationId,
                        "PLAYER_INVENTORY",
                        playerId,
                        "CLAN_STORAGE",
                        clanId,
                        normalizedReason,
                        playerId
                );
                insertLedger(connection, operationId, 0, playerId, "ITEM_INSTANCE", itemInstanceId.toString(), 1,
                        "DEBIT", normalizedReason, clanId);
                insertLedger(connection, operationId, 1, null, "ITEM_INSTANCE", itemInstanceId.toString(), 1,
                        "CREDIT", normalizedReason, clanId);

                ClanUniqueStorageDepositResult result = new ClanUniqueStorageDepositResult(
                        clanId,
                        playerId,
                        itemInstanceId,
                        nextItemVersion,
                        nextPlayerStateVersion
                );
                insertProcessed(connection, operationId, UNIQUE_DEPOSIT_OPERATION, request, uniqueDepositMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanUniqueStorageWithdrawResult withdrawUniqueItem(
            UUID operationId,
            UUID clanId,
            UUID playerId,
            UUID itemInstanceId,
            long expectedItemStateVersion,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (expectedItemStateVersion < 0) {
            throw new IllegalArgumentException("expectedItemStateVersion must be >= 0");
        }
        String normalizedReason = requireReason(reason);
        Map<String, Object> request = requestMap(
                "clan_id", clanId,
                "player_id", playerId,
                "item_instance_id", itemInstanceId,
                "expected_item_state_version", expectedItemStateVersion,
                "reason", normalizedReason
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Object result = requireReplay(processed.orElseThrow(), UNIQUE_WITHDRAW_OPERATION, request, operationId);
                    ClanUniqueStorageWithdrawResult replay = uniqueWithdrawFrom(result);
                    connection.commit();
                    return replay;
                }

                ClanRole role = lockClanMember(connection, clanId, playerId);
                requirePermission(role, ClanAssetPermission.STORAGE_WITHDRAW);
                LockedItem item = lockItem(connection, itemInstanceId);
                requireIndividual(item.definitionId());
                if (item.stateVersion() != expectedItemStateVersion) {
                    throw new ClanAssetException("stale unique-item state_version for clan withdrawal: " + itemInstanceId);
                }
                if (!"CLAN_STORAGE".equals(item.locationKind()) || !clanId.equals(item.locationId())) {
                    throw new ClanAssetException("unique item is not in authoritative storage for this clan");
                }

                UUID deliveryId = deterministicUuid(operationId, "clan-unique-delivery");
                insertUniqueDelivery(connection, deliveryId, playerId, itemInstanceId, operationId, normalizedReason);
                long nextItemVersion = increment(item.stateVersion(), "unique item", itemInstanceId);
                moveItem(
                        connection,
                        itemInstanceId,
                        item.stateVersion(),
                        "CLAN_STORAGE",
                        clanId,
                        "PENDING_DELIVERY",
                        deliveryId,
                        nextItemVersion
                );
                insertProvenance(
                        connection,
                        itemInstanceId,
                        nextItemVersion,
                        operationId,
                        "CLAN_STORAGE",
                        clanId,
                        "PENDING_DELIVERY",
                        deliveryId,
                        normalizedReason,
                        playerId
                );
                insertLedger(connection, operationId, 0, null, "ITEM_INSTANCE", itemInstanceId.toString(), 1,
                        "DEBIT", normalizedReason, clanId);
                insertLedger(connection, operationId, 1, playerId, "ITEM_INSTANCE", itemInstanceId.toString(), 1,
                        "CREDIT", normalizedReason, clanId);

                ClanUniqueStorageWithdrawResult result = new ClanUniqueStorageWithdrawResult(
                        clanId,
                        playerId,
                        itemInstanceId,
                        nextItemVersion,
                        deliveryId
                );
                insertProcessed(connection, operationId, UNIQUE_WITHDRAW_OPERATION, request, uniqueWithdrawMap(result));
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
            throw new ClanAssetException("clan commodity storage requires COMMODITY definition: " + definitionId);
        }
        return definition;
    }

    private ItemDefinition requireIndividual(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new ClanAssetException("clan unique storage requires INDIVIDUAL definition: " + definitionId);
        }
        return definition;
    }

    private static ClanRole lockClanMember(Connection connection, UUID clanId, UUID playerId) throws SQLException {
        requireClan(connection, clanId, true);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role
                FROM clan_members
                WHERE clan_id = ? AND player_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanAssetException("player is not a clan member: " + playerId);
                }
                return ClanRole.valueOf(row.getString("role"));
            }
        }
    }

    private static void requirePermission(ClanRole role, ClanAssetPermission permission) {
        boolean allowed = switch (permission) {
            case STORAGE_DEPOSIT -> true;
            case STORAGE_WITHDRAW, STORAGE_MANAGE -> role == ClanRole.LEADER || role == ClanRole.OFFICER;
            default -> false;
        };
        if (!allowed) {
            throw new ClanAssetException("clan role " + role + " lacks permission " + permission);
        }
    }

    private static void requireClan(Connection connection, UUID clanId, boolean forUpdate) throws SQLException {
        String sql = "SELECT 1 FROM clans WHERE clan_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanAssetException("Unknown clan_id: " + clanId);
                }
            }
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
                    throw new ClanAssetException("Unknown player session: " + sessionId);
                }
                return row.getObject("player_id", UUID.class);
            }
        }
    }

    private static void ensureCommodityRow(Connection connection, UUID clanId, String commodity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO clan_commodity_balances(clan_id, commodity_definition_id, quantity, state_version)
                VALUES (?, ?, 0, 0)
                ON CONFLICT (clan_id, commodity_definition_id) DO NOTHING
                """)) {
            statement.setObject(1, clanId);
            statement.setString(2, commodity);
            statement.executeUpdate();
        }
    }

    private static Optional<ClanCommodityStorageSnapshot> readCommodity(
            Connection connection,
            UUID clanId,
            String commodity,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT quantity, state_version, updated_at
                FROM clan_commodity_balances
                WHERE clan_id = ? AND commodity_definition_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, clanId);
            statement.setString(2, commodity);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ClanCommodityStorageSnapshot(
                        clanId,
                        commodity,
                        row.getLong("quantity"),
                        row.getLong("state_version"),
                        row.getTimestamp("updated_at").toInstant()
                ));
            }
        }
    }

    private static ClanCommodityStorageSnapshot updateCommodity(
            Connection connection,
            ClanCommodityStorageSnapshot current,
            long nextQuantity,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE clan_commodity_balances
                SET quantity = ?, state_version = ?, updated_at = NOW()
                WHERE clan_id = ? AND commodity_definition_id = ? AND state_version = ?
                RETURNING updated_at
                """)) {
            statement.setLong(1, nextQuantity);
            statement.setLong(2, nextVersion);
            statement.setObject(3, current.clanId());
            statement.setString(4, current.commodityDefinitionId());
            statement.setLong(5, current.stateVersion());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanAssetException("clan commodity storage changed concurrently");
                }
                return new ClanCommodityStorageSnapshot(
                        current.clanId(),
                        current.commodityDefinitionId(),
                        nextQuantity,
                        nextVersion,
                        row.getTimestamp("updated_at").toInstant()
                );
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
                    throw new ClanAssetException("Unknown unique item: " + itemInstanceId);
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

    private static void moveItem(
            Connection connection,
            UUID itemInstanceId,
            long expectedVersion,
            String expectedLocationKind,
            UUID expectedLocationId,
            String targetLocationKind,
            UUID targetLocationId,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = ?, location_id = ?, state_version = ?, updated_at = NOW()
                WHERE item_instance_id = ?
                  AND state_version = ?
                  AND location_kind = ?
                  AND location_id = ?
                """)) {
            statement.setString(1, targetLocationKind);
            statement.setObject(2, targetLocationId);
            statement.setLong(3, nextVersion);
            statement.setObject(4, itemInstanceId);
            statement.setLong(5, expectedVersion);
            statement.setString(6, expectedLocationKind);
            statement.setObject(7, expectedLocationId);
            if (statement.executeUpdate() != 1) {
                throw new ClanAssetException("unique-item custody changed concurrently: " + itemInstanceId);
            }
        }
    }

    private static void insertProvenance(
            Connection connection,
            UUID itemInstanceId,
            long sequenceNo,
            UUID operationId,
            String fromLocationKind,
            UUID fromLocationId,
            String toLocationKind,
            UUID toLocationId,
            String reason,
            UUID actorPlayerId
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
                ) VALUES (?, ?, ?, 'MOVED', ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, sequenceNo);
            statement.setObject(3, operationId);
            statement.setString(4, fromLocationKind);
            statement.setObject(5, fromLocationId);
            statement.setString(6, toLocationKind);
            statement.setObject(7, toLocationId);
            statement.setString(8, reason);
            statement.setObject(9, actorPlayerId);
            statement.executeUpdate();
        }
    }

    private static void insertCommodityDelivery(
            Connection connection,
            UUID deliveryId,
            UUID playerId,
            String commodity,
            long quantity,
            UUID sourceOperationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_commodity_deliveries(
                    delivery_id,
                    player_id,
                    commodity_definition_id,
                    quantity,
                    source_operation_id,
                    status
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

    private static void insertUniqueDelivery(
            Connection connection,
            UUID deliveryId,
            UUID playerId,
            UUID itemInstanceId,
            UUID issueOperationId,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_unique_deliveries(
                    delivery_id,
                    recipient_player_id,
                    item_instance_id,
                    status,
                    issue_operation_id,
                    issue_reason
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

    private static void insertLedger(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            String assetType,
            String assetId,
            long amount,
            String direction,
            String reason,
            UUID clanId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id,
                    line_no,
                    player_id,
                    asset_type,
                    asset_id,
                    amount,
                    direction,
                    reason,
                    related_entity_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            if (playerId == null) {
                statement.setNull(3, Types.OTHER);
            } else {
                statement.setObject(3, playerId);
            }
            statement.setString(4, assetType);
            statement.setString(5, assetId);
            statement.setLong(6, amount);
            statement.setString(7, direction);
            statement.setString(8, reason);
            statement.setString(9, clanId.toString());
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

    private static Object requireReplay(
            ProcessedOperation processed,
            String expectedType,
            Map<String, Object> request,
            UUID operationId
    ) {
        if (!expectedType.equals(processed.operationType())) {
            throw new ClanAssetException(
                    "operation_id " + operationId + " already belongs to " + processed.operationType()
            );
        }
        Map<String, Object> priorRequest = objectMap(processed.data().get("request"), "request");
        if (!priorRequest.equals(request)) {
            throw new ClanAssetException("operation_id reused with a different clan storage request: " + operationId);
        }
        Object result = processed.data().get("result");
        if (result == null) {
            throw new ClanAssetException("processed clan storage operation is missing result: " + operationId);
        }
        return result;
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            String operationType,
            Map<String, Object> request,
            Map<String, Object> result
    ) throws SQLException {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("request", request);
        data.put("result", result);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, writeJson(data));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requestMap(Object... fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("requestMap requires key/value pairs");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            String key = Objects.toString(fields[index]);
            Object value = fields[index + 1];
            result.put(key, value == null ? null : Objects.toString(value));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> commoditySnapshotMap(ClanCommodityStorageSnapshot snapshot) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("clan_id", snapshot.clanId().toString());
        result.put("commodity_definition_id", snapshot.commodityDefinitionId());
        result.put("quantity", snapshot.quantity());
        result.put("state_version", snapshot.stateVersion());
        result.put("updated_at", snapshot.updatedAt().toString());
        return result;
    }

    private static ClanCommodityStorageSnapshot commoditySnapshotFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "storage");
        return new ClanCommodityStorageSnapshot(
                uuidValue(value, "clan_id"),
                stringValue(value, "commodity_definition_id"),
                longValue(value, "quantity"),
                longValue(value, "state_version"),
                Instant.parse(stringValue(value, "updated_at"))
        );
    }

    private static Map<String, Object> commodityDepositMap(ClanCommodityStorageDepositResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("storage", commoditySnapshotMap(result.storage()));
        value.put("player_id", result.playerId().toString());
        value.put("deposited_quantity", result.depositedQuantity());
        value.put("player_state_version", result.playerStateVersion());
        return value;
    }

    private static ClanCommodityStorageDepositResult commodityDepositFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        return new ClanCommodityStorageDepositResult(
                commoditySnapshotFrom(value.get("storage")),
                uuidValue(value, "player_id"),
                longValue(value, "deposited_quantity"),
                longValue(value, "player_state_version")
        );
    }

    private static Map<String, Object> commodityWithdrawMap(ClanCommodityStorageWithdrawResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("storage", commoditySnapshotMap(result.storage()));
        value.put("player_id", result.playerId().toString());
        value.put("withdrawn_quantity", result.withdrawnQuantity());
        value.put("delivery_id", result.deliveryId().toString());
        return value;
    }

    private static ClanCommodityStorageWithdrawResult commodityWithdrawFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        return new ClanCommodityStorageWithdrawResult(
                commoditySnapshotFrom(value.get("storage")),
                uuidValue(value, "player_id"),
                longValue(value, "withdrawn_quantity"),
                uuidValue(value, "delivery_id")
        );
    }

    private static Map<String, Object> uniqueDepositMap(ClanUniqueStorageDepositResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("clan_id", result.clanId().toString());
        value.put("player_id", result.playerId().toString());
        value.put("item_instance_id", result.itemInstanceId().toString());
        value.put("item_state_version", result.itemStateVersion());
        value.put("player_state_version", result.playerStateVersion());
        return value;
    }

    private static ClanUniqueStorageDepositResult uniqueDepositFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        return new ClanUniqueStorageDepositResult(
                uuidValue(value, "clan_id"),
                uuidValue(value, "player_id"),
                uuidValue(value, "item_instance_id"),
                longValue(value, "item_state_version"),
                longValue(value, "player_state_version")
        );
    }

    private static Map<String, Object> uniqueWithdrawMap(ClanUniqueStorageWithdrawResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("clan_id", result.clanId().toString());
        value.put("player_id", result.playerId().toString());
        value.put("item_instance_id", result.itemInstanceId().toString());
        value.put("item_state_version", result.itemStateVersion());
        value.put("delivery_id", result.deliveryId().toString());
        return value;
    }

    private static ClanUniqueStorageWithdrawResult uniqueWithdrawFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        return new ClanUniqueStorageWithdrawResult(
                uuidValue(value, "clan_id"),
                uuidValue(value, "player_id"),
                uuidValue(value, "item_instance_id"),
                longValue(value, "item_state_version"),
                uuidValue(value, "delivery_id")
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ClanAssetException("Could not parse clan storage idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ClanAssetException("Could not serialize clan storage idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ClanAssetException("clan storage field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new ClanAssetException("missing clan storage field: " + field);
        }
        return Objects.toString(raw);
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(Objects.toString(raw));
        } catch (RuntimeException exception) {
            throw new ClanAssetException("invalid numeric clan storage field: " + field, exception);
        }
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ClanAssetException(message, exception);
        }
    }

    private static long increment(long current, String authority, Object id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new ClanAssetException(authority + " state_version overflow: " + id, exception);
        }
    }

    private static UUID deterministicUuid(UUID operationId, String purpose) {
        return UUID.nameUUIDFromBytes(
                (operationId + ":" + purpose).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
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

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedItem(
            String definitionId,
            String locationKind,
            UUID locationId,
            long stateVersion
    ) { }

    private record ProcessedOperation(String operationType, Map<String, Object> data) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            data = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
        }
    }
}
