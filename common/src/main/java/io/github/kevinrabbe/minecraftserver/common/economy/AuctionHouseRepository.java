package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemLocation;
import io.github.kevinrabbe.minecraftserver.common.item.ItemLocationKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import io.github.kevinrabbe.minecraftserver.common.session.SessionStatus;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Atomic fixed-price Buy-It-Now authority. No bidding, expiration, or player UI lives here. */
public final class AuctionHouseRepository {
    private static final String CREATE_OPERATION = "AUCTION_LISTING_CREATE";
    private static final String PURCHASE_OPERATION = "AUCTION_LISTING_PURCHASE";
    private static final String CANCEL_OPERATION = "AUCTION_LISTING_CANCEL";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final PlayerStateRepository playerStates;

    public AuctionHouseRepository(DataSource dataSource, ItemCatalog itemCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.playerStates = new PlayerStateRepository(dataSource);
    }

    /**
     * Atomically removes one individual item from the seller's serialized live state and moves its authority to
     * auction escrow. The caller supplies the already-rendered new player payload with that item removed.
     */
    public AuctionListingCreateResult createListing(
            UUID operationId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            UUID itemInstanceId,
            long expectedItemStateVersion,
            long priceMinor,
            String logicalZoneId,
            String entryPoint,
            byte[] newStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        if (expectedPlayerStateVersion < 0 || expectedItemStateVersion < 0) {
            throw new IllegalArgumentException("expected state versions must be >= 0");
        }
        if (priceMinor <= 0) {
            throw new IllegalArgumentException("priceMinor must be > 0");
        }
        Objects.requireNonNull(newStatePayload, "newStatePayload");
        String normalizedZone = normalizeOptional(logicalZoneId);
        String normalizedEntry = normalizeOptional(entryPoint);
        String normalizedReason = requireReason(reason);
        String payloadSha256 = sha256(newStatePayload);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedCreate> processed = findProcessedCreate(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedCreate previous = processed.orElseThrow();
                    previous.requireSameRequest(
                            sessionId,
                            normalizedBackendId,
                            expectedPlayerStateVersion,
                            itemInstanceId,
                            expectedItemStateVersion,
                            priceMinor,
                            normalizedZone,
                            normalizedEntry,
                            payloadSha256,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                LiveSession sellerSession = lockLiveSession(
                        connection,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion
                );
                LockedItem item = lockItem(connection, itemInstanceId);
                ItemDefinition definition = requireIndividualDefinition(item.definitionId());
                if (item.stateVersion() != expectedItemStateVersion) {
                    throw new AuctionHouseException("Stale item state_version for listing: " + itemInstanceId);
                }
                ItemLocation sellerLocation = ItemLocation.playerInventory(sellerSession.playerId());
                if (!sellerLocation.equals(item.location())) {
                    throw new AuctionHouseException("Seller does not own authoritative item inventory custody");
                }

                UUID listingId = UUID.randomUUID();
                long escrowVersion = incrementVersion(item.stateVersion(), "item", itemInstanceId);
                insertActiveListing(
                        connection,
                        listingId,
                        sellerSession.playerId(),
                        itemInstanceId,
                        escrowVersion,
                        priceMinor,
                        operationId
                );

                long playerStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion,
                        normalizedZone,
                        normalizedEntry,
                        newStatePayload
                );

                ItemLocation escrowLocation = ItemLocation.auctionEscrow(listingId);
                updateItemLocation(
                        connection,
                        itemInstanceId,
                        item.stateVersion(),
                        escrowVersion,
                        escrowLocation
                );
                insertProvenance(
                        connection,
                        itemInstanceId,
                        escrowVersion,
                        operationId,
                        sellerLocation,
                        escrowLocation,
                        normalizedReason,
                        sellerSession.playerId()
                );
                insertItemLedger(
                        connection,
                        operationId,
                        0,
                        sellerSession.playerId(),
                        itemInstanceId,
                        "DEBIT",
                        normalizedReason
                );

                AuctionListingCreateResult result = new AuctionListingCreateResult(
                        listingId,
                        sellerSession.playerId(),
                        itemInstanceId,
                        definition.definitionId(),
                        escrowVersion,
                        priceMinor,
                        playerStateVersion
                );
                insertProcessedCreate(
                        connection,
                        operationId,
                        result,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion,
                        expectedItemStateVersion,
                        normalizedZone,
                        normalizedEntry,
                        payloadSha256,
                        normalizedReason
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Atomically transfers Coins buyer->seller and settles the item into buyer pending delivery. */
    public AuctionPurchaseResult purchase(
            UUID operationId,
            UUID listingId,
            UUID buyerPlayerId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(buyerPlayerId, "buyerPlayerId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedPurchase> processed = findProcessedPurchase(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedPurchase previous = processed.orElseThrow();
                    previous.requireSameRequest(listingId, buyerPlayerId, normalizedReason, operationId);
                    connection.commit();
                    return previous.result();
                }

                AuctionListing listing = lockListing(connection, listingId);
                requireActive(listing);
                if (listing.sellerPlayerId().equals(buyerPlayerId)) {
                    throw new AuctionHouseException("Seller cannot purchase their own listing");
                }
                requirePlayer(connection, buyerPlayerId);

                LockedItem item = lockEscrowItem(connection, listing);
                Map<UUID, CoinWalletSnapshot> wallets = lockWallets(
                        connection,
                        buyerPlayerId,
                        listing.sellerPlayerId()
                );
                CoinWalletSnapshot buyerWallet = requireWallet(wallets, buyerPlayerId);
                CoinWalletSnapshot sellerWallet = requireWallet(wallets, listing.sellerPlayerId());
                if (buyerWallet.balanceMinor() < listing.priceMinor()) {
                    throw new AuctionHouseException("Buyer has insufficient Coin balance");
                }

                long buyerBalance = buyerWallet.balanceMinor() - listing.priceMinor();
                long sellerBalance = addExact(
                        sellerWallet.balanceMinor(),
                        listing.priceMinor(),
                        "Seller Coin balance overflow"
                );
                long buyerWalletVersion = incrementVersion(
                        buyerWallet.stateVersion(),
                        "wallet",
                        buyerPlayerId
                );
                long sellerWalletVersion = incrementVersion(
                        sellerWallet.stateVersion(),
                        "wallet",
                        listing.sellerPlayerId()
                );

                UUID deliveryId = UUID.randomUUID();
                insertPendingDelivery(
                        connection,
                        deliveryId,
                        buyerPlayerId,
                        item.itemInstanceId(),
                        operationId,
                        normalizedReason
                );
                updateWallet(
                        connection,
                        buyerPlayerId,
                        buyerWallet.stateVersion(),
                        buyerBalance,
                        buyerWalletVersion
                );
                updateWallet(
                        connection,
                        listing.sellerPlayerId(),
                        sellerWallet.stateVersion(),
                        sellerBalance,
                        sellerWalletVersion
                );

                long itemVersion = incrementVersion(item.stateVersion(), "item", item.itemInstanceId());
                ItemLocation pendingLocation = ItemLocation.pendingDelivery(deliveryId);
                updateItemLocation(
                        connection,
                        item.itemInstanceId(),
                        item.stateVersion(),
                        itemVersion,
                        pendingLocation
                );
                insertProvenance(
                        connection,
                        item.itemInstanceId(),
                        itemVersion,
                        operationId,
                        ItemLocation.auctionEscrow(listing.listingId()),
                        pendingLocation,
                        normalizedReason,
                        buyerPlayerId
                );
                markListingSold(connection, listing.listingId(), operationId, buyerPlayerId, deliveryId);

                insertCoinLedger(
                        connection,
                        operationId,
                        0,
                        buyerPlayerId,
                        listing.priceMinor(),
                        "DEBIT",
                        normalizedReason
                );
                insertCoinLedger(
                        connection,
                        operationId,
                        1,
                        listing.sellerPlayerId(),
                        listing.priceMinor(),
                        "CREDIT",
                        normalizedReason
                );
                insertItemLedger(
                        connection,
                        operationId,
                        2,
                        buyerPlayerId,
                        item.itemInstanceId(),
                        "CREDIT",
                        normalizedReason
                );

                AuctionPurchaseResult result = new AuctionPurchaseResult(
                        listing.listingId(),
                        listing.sellerPlayerId(),
                        buyerPlayerId,
                        item.itemInstanceId(),
                        item.definitionId(),
                        deliveryId,
                        itemVersion,
                        listing.priceMinor(),
                        buyerBalance,
                        buyerWalletVersion,
                        sellerBalance,
                        sellerWalletVersion
                );
                insertProcessedPurchase(connection, operationId, result, normalizedReason);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Cancels an active listing and settles the item into seller pending delivery. */
    public AuctionCancelResult cancel(
            UUID operationId,
            UUID listingId,
            UUID sellerPlayerId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedCancel> processed = findProcessedCancel(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedCancel previous = processed.orElseThrow();
                    previous.requireSameRequest(listingId, sellerPlayerId, normalizedReason, operationId);
                    connection.commit();
                    return previous.result();
                }

                AuctionListing listing = lockListing(connection, listingId);
                requireActive(listing);
                if (!listing.sellerPlayerId().equals(sellerPlayerId)) {
                    throw new AuctionHouseException("Only the listing seller may cancel it");
                }

                LockedItem item = lockEscrowItem(connection, listing);
                UUID deliveryId = UUID.randomUUID();
                insertPendingDelivery(
                        connection,
                        deliveryId,
                        sellerPlayerId,
                        item.itemInstanceId(),
                        operationId,
                        normalizedReason
                );

                long itemVersion = incrementVersion(item.stateVersion(), "item", item.itemInstanceId());
                ItemLocation pendingLocation = ItemLocation.pendingDelivery(deliveryId);
                updateItemLocation(
                        connection,
                        item.itemInstanceId(),
                        item.stateVersion(),
                        itemVersion,
                        pendingLocation
                );
                insertProvenance(
                        connection,
                        item.itemInstanceId(),
                        itemVersion,
                        operationId,
                        ItemLocation.auctionEscrow(listing.listingId()),
                        pendingLocation,
                        normalizedReason,
                        sellerPlayerId
                );
                markListingCancelled(connection, listing.listingId(), operationId, deliveryId);
                insertItemLedger(
                        connection,
                        operationId,
                        0,
                        sellerPlayerId,
                        item.itemInstanceId(),
                        "CREDIT",
                        normalizedReason
                );

                AuctionCancelResult result = new AuctionCancelResult(
                        listing.listingId(),
                        sellerPlayerId,
                        item.itemInstanceId(),
                        item.definitionId(),
                        deliveryId,
                        itemVersion
                );
                insertProcessedCancel(connection, operationId, result, normalizedReason);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public AuctionListing load(UUID listingId) throws SQLException {
        Objects.requireNonNull(listingId, "listingId");
        try (Connection connection = dataSource.getConnection()) {
            return readListing(connection, listingId, false);
        }
    }

    private ItemDefinition requireIndividualDefinition(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new AuctionHouseException("Auction House accepts only INDIVIDUAL definitions");
        }
        return definition;
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
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SessionConflictException("Unknown session: " + sessionId);
                }
                UUID playerId = results.getObject("player_id", UUID.class);
                String ownerBackendId = results.getString("owner_backend_id");
                long stateVersion = results.getLong("state_version");
                SessionStatus status = SessionStatus.valueOf(results.getString("status"));
                boolean leaseValid = results.getBoolean("lease_valid");
                if (!backendId.equals(ownerBackendId)
                        || stateVersion != expectedStateVersion
                        || !leaseValid
                        || (status != SessionStatus.ACTIVE && status != SessionStatus.RECOVERING)) {
                    throw new SessionConflictException("Auction listing does not match authoritative live session");
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
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new AuctionHouseException("Unknown item_instance_id: " + itemInstanceId);
                }
                return new LockedItem(
                        itemInstanceId,
                        results.getString("definition_id"),
                        new ItemLocation(
                                ItemLocationKind.valueOf(results.getString("location_kind")),
                                results.getObject("location_id", UUID.class)
                        ),
                        results.getLong("state_version")
                );
            }
        }
    }

    private static LockedItem lockEscrowItem(Connection connection, AuctionListing listing) throws SQLException {
        LockedItem item = lockItem(connection, listing.itemInstanceId());
        if (item.stateVersion() != listing.escrowItemVersion()
                || !ItemLocation.auctionEscrow(listing.listingId()).equals(item.location())) {
            throw new AuctionHouseException("Auction escrow authority no longer matches listing");
        }
        return item;
    }

    private static AuctionListing lockListing(Connection connection, UUID listingId) throws SQLException {
        return readListing(connection, listingId, true);
    }

    private static AuctionListing readListing(
            Connection connection,
            UUID listingId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT seller_player_id,
                       item_instance_id,
                       escrow_item_version,
                       price_minor,
                       status,
                       create_operation_id,
                       settle_operation_id,
                       buyer_player_id,
                       settlement_delivery_id,
                       created_at,
                       settled_at
                FROM auction_listings
                WHERE listing_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, listingId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new AuctionHouseException("Unknown listing_id: " + listingId);
                }
                Timestamp settledAt = results.getTimestamp("settled_at");
                return new AuctionListing(
                        listingId,
                        results.getObject("seller_player_id", UUID.class),
                        results.getObject("item_instance_id", UUID.class),
                        results.getLong("escrow_item_version"),
                        results.getLong("price_minor"),
                        AuctionListingStatus.valueOf(results.getString("status")),
                        results.getObject("create_operation_id", UUID.class),
                        results.getObject("settle_operation_id", UUID.class),
                        results.getObject("buyer_player_id", UUID.class),
                        results.getObject("settlement_delivery_id", UUID.class),
                        results.getTimestamp("created_at").toInstant(),
                        settledAt == null ? null : settledAt.toInstant()
                );
            }
        }
    }

    private static void requireActive(AuctionListing listing) {
        if (listing.status() != AuctionListingStatus.ACTIVE) {
            throw new AuctionHouseException("Auction listing is not active: " + listing.listingId());
        }
    }

    private static void insertActiveListing(
            Connection connection,
            UUID listingId,
            UUID sellerPlayerId,
            UUID itemInstanceId,
            long escrowItemVersion,
            long priceMinor,
            UUID createOperationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auction_listings(
                    listing_id,
                    seller_player_id,
                    item_instance_id,
                    escrow_item_version,
                    price_minor,
                    create_operation_id
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, listingId);
            statement.setObject(2, sellerPlayerId);
            statement.setObject(3, itemInstanceId);
            statement.setLong(4, escrowItemVersion);
            statement.setLong(5, priceMinor);
            statement.setObject(6, createOperationId);
            statement.executeUpdate();
        }
    }

    private static void insertPendingDelivery(
            Connection connection,
            UUID deliveryId,
            UUID recipientPlayerId,
            UUID itemInstanceId,
            UUID issueOperationId,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_unique_deliveries(
                    delivery_id,
                    recipient_player_id,
                    item_instance_id,
                    issue_operation_id,
                    issue_reason
                )
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, recipientPlayerId);
            statement.setObject(3, itemInstanceId);
            statement.setObject(4, issueOperationId);
            statement.setString(5, reason);
            statement.executeUpdate();
        }
    }

    private static void markListingSold(
            Connection connection,
            UUID listingId,
            UUID settleOperationId,
            UUID buyerPlayerId,
            UUID deliveryId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE auction_listings
                SET status = 'SOLD',
                    settle_operation_id = ?,
                    buyer_player_id = ?,
                    settlement_delivery_id = ?,
                    settled_at = NOW()
                WHERE listing_id = ?
                  AND status = 'ACTIVE'
                """)) {
            statement.setObject(1, settleOperationId);
            statement.setObject(2, buyerPlayerId);
            statement.setObject(3, deliveryId);
            statement.setObject(4, listingId);
            if (statement.executeUpdate() != 1) {
                throw new AuctionHouseException("Auction listing changed concurrently");
            }
        }
    }

    private static void markListingCancelled(
            Connection connection,
            UUID listingId,
            UUID settleOperationId,
            UUID deliveryId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE auction_listings
                SET status = 'CANCELLED',
                    settle_operation_id = ?,
                    settlement_delivery_id = ?,
                    settled_at = NOW()
                WHERE listing_id = ?
                  AND status = 'ACTIVE'
                """)) {
            statement.setObject(1, settleOperationId);
            statement.setObject(2, deliveryId);
            statement.setObject(3, listingId);
            if (statement.executeUpdate() != 1) {
                throw new AuctionHouseException("Auction listing changed concurrently");
            }
        }
    }

    private static void updateItemLocation(
            Connection connection,
            UUID itemInstanceId,
            long expectedVersion,
            long newVersion,
            ItemLocation target
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = ?,
                    location_id = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ?
                  AND state_version = ?
                """)) {
            statement.setString(1, target.kind().name());
            statement.setObject(2, target.locationId());
            statement.setLong(3, newVersion);
            statement.setObject(4, itemInstanceId);
            statement.setLong(5, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new AuctionHouseException("Item authority changed concurrently");
            }
        }
    }

    private static void insertProvenance(
            Connection connection,
            UUID itemInstanceId,
            long sequenceNo,
            UUID operationId,
            ItemLocation from,
            ItemLocation to,
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
                )
                VALUES (?, ?, ?, 'MOVED', ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, sequenceNo);
            statement.setObject(3, operationId);
            statement.setString(4, from.kind().name());
            statement.setObject(5, from.locationId());
            statement.setString(6, to.kind().name());
            statement.setObject(7, to.locationId());
            statement.setString(8, reason);
            statement.setObject(9, actorPlayerId);
            statement.executeUpdate();
        }
    }

    private static Map<UUID, CoinWalletSnapshot> lockWallets(
            Connection connection,
            UUID firstPlayerId,
            UUID secondPlayerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id, balance_minor, state_version
                FROM wallets
                WHERE player_id IN (?, ?)
                ORDER BY player_id
                FOR UPDATE
                """)) {
            statement.setObject(1, firstPlayerId);
            statement.setObject(2, secondPlayerId);
            try (ResultSet results = statement.executeQuery()) {
                HashMap<UUID, CoinWalletSnapshot> wallets = new HashMap<>();
                while (results.next()) {
                    UUID playerId = results.getObject("player_id", UUID.class);
                    wallets.put(playerId, new CoinWalletSnapshot(
                            playerId,
                            results.getLong("balance_minor"),
                            results.getLong("state_version")
                    ));
                }
                return wallets;
            }
        }
    }

    private static CoinWalletSnapshot requireWallet(Map<UUID, CoinWalletSnapshot> wallets, UUID playerId) {
        CoinWalletSnapshot wallet = wallets.get(playerId);
        if (wallet == null) {
            throw new AuctionHouseException("Wallet does not exist for player_id " + playerId);
        }
        return wallet;
    }

    private static void updateWallet(
            Connection connection,
            UUID playerId,
            long expectedVersion,
            long newBalance,
            long newVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wallets
                SET balance_minor = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE player_id = ?
                  AND state_version = ?
                """)) {
            statement.setLong(1, newBalance);
            statement.setLong(2, newVersion);
            statement.setObject(3, playerId);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new AuctionHouseException("Wallet authority changed concurrently for " + playerId);
            }
        }
    }

    private static void insertCoinLedger(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            long amountMinor,
            String direction,
            String reason
    ) throws SQLException {
        insertLedger(
                connection,
                operationId,
                lineNo,
                playerId,
                CoinCurrency.LEDGER_ASSET_TYPE,
                CoinCurrency.LEDGER_ASSET_ID,
                amountMinor,
                direction,
                reason
        );
    }

    private static void insertItemLedger(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            UUID itemInstanceId,
            String direction,
            String reason
    ) throws SQLException {
        insertLedger(
                connection,
                operationId,
                lineNo,
                playerId,
                "ITEM_INSTANCE",
                itemInstanceId.toString(),
                1,
                direction,
                reason
        );
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
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id,
                    line_no,
                    player_id,
                    asset_type,
                    asset_id,
                    amount,
                    direction,
                    reason
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
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

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new AuctionHouseException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static void insertProcessedCreate(
            Connection connection,
            UUID operationId,
            AuctionListingCreateResult result,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            long expectedItemStateVersion,
            String logicalZoneId,
            String entryPoint,
            String payloadSha256,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'listing_id', ?,
                    'seller_player_id', ?,
                    'item_instance_id', ?,
                    'definition_id', ?,
                    'escrow_item_version', ?,
                    'price_minor', ?,
                    'player_state_version', ?,
                    'session_id', ?,
                    'backend_id', ?,
                    'expected_player_state_version', ?,
                    'expected_item_state_version', ?,
                    'logical_zone_id', ?,
                    'entry_point', ?,
                    'payload_sha256', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, CREATE_OPERATION);
            statement.setString(3, result.listingId().toString());
            statement.setString(4, result.sellerPlayerId().toString());
            statement.setString(5, result.itemInstanceId().toString());
            statement.setString(6, result.definitionId());
            statement.setLong(7, result.escrowItemVersion());
            statement.setLong(8, result.priceMinor());
            statement.setLong(9, result.playerStateVersion());
            statement.setString(10, sessionId.toString());
            statement.setString(11, backendId);
            statement.setLong(12, expectedPlayerStateVersion);
            statement.setLong(13, expectedItemStateVersion);
            statement.setString(14, logicalZoneId);
            statement.setString(15, entryPoint);
            statement.setString(16, payloadSha256);
            statement.setString(17, reason);
            statement.executeUpdate();
        }
    }

    private static void insertProcessedPurchase(
            Connection connection,
            UUID operationId,
            AuctionPurchaseResult result,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'listing_id', ?,
                    'seller_player_id', ?,
                    'buyer_player_id', ?,
                    'item_instance_id', ?,
                    'definition_id', ?,
                    'delivery_id', ?,
                    'item_state_version', ?,
                    'price_minor', ?,
                    'buyer_balance_minor', ?,
                    'buyer_wallet_version', ?,
                    'seller_balance_minor', ?,
                    'seller_wallet_version', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, PURCHASE_OPERATION);
            statement.setString(3, result.listingId().toString());
            statement.setString(4, result.sellerPlayerId().toString());
            statement.setString(5, result.buyerPlayerId().toString());
            statement.setString(6, result.itemInstanceId().toString());
            statement.setString(7, result.definitionId());
            statement.setString(8, result.deliveryId().toString());
            statement.setLong(9, result.itemStateVersion());
            statement.setLong(10, result.priceMinor());
            statement.setLong(11, result.buyerBalanceMinor());
            statement.setLong(12, result.buyerWalletVersion());
            statement.setLong(13, result.sellerBalanceMinor());
            statement.setLong(14, result.sellerWalletVersion());
            statement.setString(15, reason);
            statement.executeUpdate();
        }
    }

    private static void insertProcessedCancel(
            Connection connection,
            UUID operationId,
            AuctionCancelResult result,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'listing_id', ?,
                    'seller_player_id', ?,
                    'item_instance_id', ?,
                    'definition_id', ?,
                    'delivery_id', ?,
                    'item_state_version', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, CANCEL_OPERATION);
            statement.setString(3, result.listingId().toString());
            statement.setString(4, result.sellerPlayerId().toString());
            statement.setString(5, result.itemInstanceId().toString());
            statement.setString(6, result.definitionId());
            statement.setString(7, result.deliveryId().toString());
            statement.setLong(8, result.itemStateVersion());
            statement.setString(9, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedCreate> findProcessedCreate(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'listing_id' AS listing_id,
                       result ->> 'seller_player_id' AS seller_player_id,
                       result ->> 'item_instance_id' AS item_instance_id,
                       result ->> 'definition_id' AS definition_id,
                       result ->> 'escrow_item_version' AS escrow_item_version,
                       result ->> 'price_minor' AS price_minor,
                       result ->> 'player_state_version' AS player_state_version,
                       result ->> 'session_id' AS session_id,
                       result ->> 'backend_id' AS backend_id,
                       result ->> 'expected_player_state_version' AS expected_player_state_version,
                       result ->> 'expected_item_state_version' AS expected_item_state_version,
                       result ->> 'logical_zone_id' AS logical_zone_id,
                       result ->> 'entry_point' AS entry_point,
                       result ->> 'payload_sha256' AS payload_sha256,
                       result ->> 'reason' AS reason
                FROM processed_operations WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                requireOperationType(results.getString("operation_type"), CREATE_OPERATION, operationId);
                try {
                    AuctionListingCreateResult result = new AuctionListingCreateResult(
                            UUID.fromString(required(results, "listing_id")),
                            UUID.fromString(required(results, "seller_player_id")),
                            UUID.fromString(required(results, "item_instance_id")),
                            required(results, "definition_id"),
                            Long.parseLong(required(results, "escrow_item_version")),
                            Long.parseLong(required(results, "price_minor")),
                            Long.parseLong(required(results, "player_state_version"))
                    );
                    return Optional.of(new ProcessedCreate(
                            result,
                            UUID.fromString(required(results, "session_id")),
                            required(results, "backend_id"),
                            Long.parseLong(required(results, "expected_player_state_version")),
                            Long.parseLong(required(results, "expected_item_state_version")),
                            results.getString("logical_zone_id"),
                            results.getString("entry_point"),
                            required(results, "payload_sha256"),
                            required(results, "reason")
                    ));
                } catch (IllegalArgumentException exception) {
                    throw malformedProcessed(operationId, exception);
                }
            }
        }
    }

    private static Optional<ProcessedPurchase> findProcessedPurchase(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'listing_id' AS listing_id,
                       result ->> 'seller_player_id' AS seller_player_id,
                       result ->> 'buyer_player_id' AS buyer_player_id,
                       result ->> 'item_instance_id' AS item_instance_id,
                       result ->> 'definition_id' AS definition_id,
                       result ->> 'delivery_id' AS delivery_id,
                       result ->> 'item_state_version' AS item_state_version,
                       result ->> 'price_minor' AS price_minor,
                       result ->> 'buyer_balance_minor' AS buyer_balance_minor,
                       result ->> 'buyer_wallet_version' AS buyer_wallet_version,
                       result ->> 'seller_balance_minor' AS seller_balance_minor,
                       result ->> 'seller_wallet_version' AS seller_wallet_version,
                       result ->> 'reason' AS reason
                FROM processed_operations WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                requireOperationType(results.getString("operation_type"), PURCHASE_OPERATION, operationId);
                try {
                    AuctionPurchaseResult result = new AuctionPurchaseResult(
                            UUID.fromString(required(results, "listing_id")),
                            UUID.fromString(required(results, "seller_player_id")),
                            UUID.fromString(required(results, "buyer_player_id")),
                            UUID.fromString(required(results, "item_instance_id")),
                            required(results, "definition_id"),
                            UUID.fromString(required(results, "delivery_id")),
                            Long.parseLong(required(results, "item_state_version")),
                            Long.parseLong(required(results, "price_minor")),
                            Long.parseLong(required(results, "buyer_balance_minor")),
                            Long.parseLong(required(results, "buyer_wallet_version")),
                            Long.parseLong(required(results, "seller_balance_minor")),
                            Long.parseLong(required(results, "seller_wallet_version"))
                    );
                    return Optional.of(new ProcessedPurchase(result, required(results, "reason")));
                } catch (IllegalArgumentException exception) {
                    throw malformedProcessed(operationId, exception);
                }
            }
        }
    }

    private static Optional<ProcessedCancel> findProcessedCancel(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'listing_id' AS listing_id,
                       result ->> 'seller_player_id' AS seller_player_id,
                       result ->> 'item_instance_id' AS item_instance_id,
                       result ->> 'definition_id' AS definition_id,
                       result ->> 'delivery_id' AS delivery_id,
                       result ->> 'item_state_version' AS item_state_version,
                       result ->> 'reason' AS reason
                FROM processed_operations WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                requireOperationType(results.getString("operation_type"), CANCEL_OPERATION, operationId);
                try {
                    AuctionCancelResult result = new AuctionCancelResult(
                            UUID.fromString(required(results, "listing_id")),
                            UUID.fromString(required(results, "seller_player_id")),
                            UUID.fromString(required(results, "item_instance_id")),
                            required(results, "definition_id"),
                            UUID.fromString(required(results, "delivery_id")),
                            Long.parseLong(required(results, "item_state_version"))
                    );
                    return Optional.of(new ProcessedCancel(result, required(results, "reason")));
                } catch (IllegalArgumentException exception) {
                    throw malformedProcessed(operationId, exception);
                }
            }
        }
    }

    private static void requireOperationType(String actual, String expected, UUID operationId) {
        if (!expected.equals(actual)) {
            throw new AuctionHouseException("operation_id already belongs to " + actual + ": " + operationId);
        }
    }

    private static String required(ResultSet results, String column) throws SQLException {
        String value = results.getString(column);
        if (value == null || value.isBlank()) {
            throw new AuctionHouseException("Processed Auction House operation is missing " + column);
        }
        return value;
    }

    private static AuctionHouseException malformedProcessed(UUID operationId, IllegalArgumentException cause) {
        return new AuctionHouseException("Malformed persisted Auction House operation " + operationId, cause);
    }

    private static long incrementVersion(long current, String type, UUID id) {
        return addExact(current, 1, type + " state_version overflow for " + id);
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new AuctionHouseException(message, exception);
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

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
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

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LiveSession(UUID playerId) {
    }

    private record LockedItem(
            UUID itemInstanceId,
            String definitionId,
            ItemLocation location,
            long stateVersion
    ) {
    }

    private record ProcessedCreate(
            AuctionListingCreateResult result,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            long expectedItemStateVersion,
            String logicalZoneId,
            String entryPoint,
            String payloadSha256,
            String reason
    ) {
        void requireSameRequest(
                UUID requestedSessionId,
                String requestedBackendId,
                long requestedPlayerStateVersion,
                UUID requestedItemInstanceId,
                long requestedItemStateVersion,
                long requestedPriceMinor,
                String requestedLogicalZoneId,
                String requestedEntryPoint,
                String requestedPayloadSha256,
                String requestedReason,
                UUID operationId
        ) {
            if (!sessionId.equals(requestedSessionId)
                    || !backendId.equals(requestedBackendId)
                    || expectedPlayerStateVersion != requestedPlayerStateVersion
                    || !result.itemInstanceId().equals(requestedItemInstanceId)
                    || expectedItemStateVersion != requestedItemStateVersion
                    || result.priceMinor() != requestedPriceMinor
                    || !Objects.equals(logicalZoneId, requestedLogicalZoneId)
                    || !Objects.equals(entryPoint, requestedEntryPoint)
                    || !payloadSha256.equals(requestedPayloadSha256)
                    || !reason.equals(requestedReason)) {
                throw new AuctionHouseException(
                        "operation_id was already used for a different auction listing request: " + operationId
                );
            }
        }
    }

    private record ProcessedPurchase(AuctionPurchaseResult result, String reason) {
        void requireSameRequest(UUID listingId, UUID buyerPlayerId, String requestedReason, UUID operationId) {
            if (!result.listingId().equals(listingId)
                    || !result.buyerPlayerId().equals(buyerPlayerId)
                    || !reason.equals(requestedReason)) {
                throw new AuctionHouseException(
                        "operation_id was already used for a different auction purchase request: " + operationId
                );
            }
        }
    }

    private record ProcessedCancel(AuctionCancelResult result, String reason) {
        void requireSameRequest(UUID listingId, UUID sellerPlayerId, String requestedReason, UUID operationId) {
            if (!result.listingId().equals(listingId)
                    || !result.sellerPlayerId().equals(sellerPlayerId)
                    || !reason.equals(requestedReason)) {
                throw new AuctionHouseException(
                        "operation_id was already used for a different auction cancellation request: " + operationId
                );
            }
        }
    }
}
