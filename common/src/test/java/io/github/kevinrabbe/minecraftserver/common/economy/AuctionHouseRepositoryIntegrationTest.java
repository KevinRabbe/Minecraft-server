package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemLocation;
import io.github.kevinrabbe.minecraftserver.common.item.PendingDeliveryStatus;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityResult;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemInstance;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateSnapshot;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class AuctionHouseRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private UniqueItemAuthorityRepository items;
    private PendingUniqueDeliveryRepository deliveries;
    private CoinWalletRepository wallets;
    private AuctionHouseRepository auctions;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                6
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);

        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                "equipment.auction_sword",
                "IRON_SWORD",
                "Auction Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        deliveries = new PendingUniqueDeliveryRepository(dataSource, catalog);
        wallets = new CoinWalletRepository(dataSource);
        auctions = new AuctionHouseRepository(dataSource, catalog);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        auction_listings,
                        pending_unique_deliveries,
                        item_provenance,
                        item_instances,
                        wallets,
                        transfer_tickets,
                        economic_ledger,
                        processed_operations,
                        player_sessions,
                        zone_instances,
                        backends,
                        player_state,
                        player_names,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void createListingAtomicallyMovesPlayerStateAndItemIntoEscrow() throws SQLException {
        UUID seller = createPlayer("AuctionSeller");
        UniqueItemAuthorityResult item = createSword(seller);
        SessionLease lease = sessions.openSession(seller, "paper-a", null, LEASE);
        byte[] payloadWithoutItem = new byte[]{4, 2};
        UUID operationId = UUID.randomUUID();

        AuctionListingCreateResult created = auctions.createListing(
                operationId,
                lease.sessionId(),
                "paper-a",
                0,
                item.itemInstanceId(),
                0,
                12_345,
                "city",
                "auction-house",
                payloadWithoutItem,
                "auction.list"
        );
        AuctionListingCreateResult retry = auctions.createListing(
                operationId,
                lease.sessionId(),
                "paper-a",
                0,
                item.itemInstanceId(),
                0,
                12_345,
                "city",
                "auction-house",
                payloadWithoutItem,
                "auction.list"
        );

        assertEquals(created, retry);
        AuctionListing listing = auctions.load(created.listingId());
        assertEquals(AuctionListingStatus.ACTIVE, listing.status());
        assertEquals(seller, listing.sellerPlayerId());
        assertEquals(12_345, listing.priceMinor());
        assertEquals(1, listing.escrowItemVersion());

        UniqueItemInstance authoritativeItem = items.load(item.itemInstanceId());
        assertEquals(ItemLocation.auctionEscrow(created.listingId()), authoritativeItem.location());
        assertEquals(1, authoritativeItem.stateVersion());

        PlayerStateSnapshot state = states.load(seller);
        assertEquals(1, state.stateVersion());
        assertEquals("city", state.logicalZoneId());
        assertEquals("auction-house", state.entryPoint());
        assertArrayEquals(payloadWithoutItem, state.statePayload());

        assertEquals(2, count("SELECT COUNT(*) FROM item_provenance"));
        assertEquals(2, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(2, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void purchaseAtomicallyMovesCoinsAndSettlesItemIntoBuyerPendingDelivery() throws SQLException {
        UUID seller = createPlayer("SellerBuyTest");
        UUID buyer = createPlayer("BuyerBuyTest");
        AuctionListingCreateResult listing = createActiveListing(seller, 2_500);
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 10_000, "test.seed_buyer");
        UUID operationId = UUID.randomUUID();

        AuctionPurchaseResult first = auctions.purchase(
                operationId,
                listing.listingId(),
                buyer,
                "auction.purchase"
        );
        AuctionPurchaseResult retry = auctions.purchase(
                operationId,
                listing.listingId(),
                buyer,
                "auction.purchase"
        );

        assertEquals(first, retry);
        assertEquals(7_500, wallets.load(buyer).balanceMinor());
        assertEquals(2_500, wallets.load(seller).balanceMinor());
        assertEquals(1, first.itemStateVersion() - listing.escrowItemVersion());

        AuctionListing settled = auctions.load(listing.listingId());
        assertEquals(AuctionListingStatus.SOLD, settled.status());
        assertEquals(buyer, settled.buyerPlayerId());
        assertEquals(first.deliveryId(), settled.settlementDeliveryId());

        assertEquals(PendingDeliveryStatus.PENDING, deliveries.load(first.deliveryId()).status());
        assertEquals(buyer, deliveries.load(first.deliveryId()).recipientPlayerId());
        assertEquals(
                ItemLocation.pendingDelivery(first.deliveryId()),
                items.load(first.itemInstanceId()).location()
        );

        // create item credit + list item debit + buyer seed credit + purchase coin debit/credit + item credit
        assertEquals(6, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(4, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void cancelReturnsEscrowedItemThroughSellerPendingDeliveryWithoutCoinMovement() throws SQLException {
        UUID seller = createPlayer("CancelSeller");
        AuctionListingCreateResult listing = createActiveListing(seller, 9_900);
        long sellerBalanceBefore = wallets.load(seller).balanceMinor();
        UUID operationId = UUID.randomUUID();

        AuctionCancelResult first = auctions.cancel(
                operationId,
                listing.listingId(),
                seller,
                "auction.cancel"
        );
        AuctionCancelResult retry = auctions.cancel(
                operationId,
                listing.listingId(),
                seller,
                "auction.cancel"
        );

        assertEquals(first, retry);
        assertEquals(sellerBalanceBefore, wallets.load(seller).balanceMinor());
        assertEquals(AuctionListingStatus.CANCELLED, auctions.load(listing.listingId()).status());
        assertEquals(seller, deliveries.load(first.deliveryId()).recipientPlayerId());
        assertEquals(
                ItemLocation.pendingDelivery(first.deliveryId()),
                items.load(first.itemInstanceId()).location()
        );
    }

    @Test
    void stalePlayerStateListingAttemptRollsBackEscrowMoveAndListing() throws SQLException {
        UUID seller = createPlayer("StaleSeller");
        UniqueItemAuthorityResult item = createSword(seller);
        SessionLease lease = sessions.openSession(seller, "paper-a", null, LEASE);
        states.commit(lease.sessionId(), "paper-a", 0, "city", null, new byte[]{9});
        long listingsBefore = count("SELECT COUNT(*) FROM auction_listings");
        long provenanceBefore = count("SELECT COUNT(*) FROM item_provenance");

        assertThrows(
                SessionConflictException.class,
                () -> auctions.createListing(
                        UUID.randomUUID(),
                        lease.sessionId(),
                        "paper-a",
                        0,
                        item.itemInstanceId(),
                        0,
                        500,
                        "city",
                        null,
                        new byte[]{1},
                        "auction.list"
                )
        );

        assertEquals(listingsBefore, count("SELECT COUNT(*) FROM auction_listings"));
        assertEquals(provenanceBefore, count("SELECT COUNT(*) FROM item_provenance"));
        assertEquals(ItemLocation.playerInventory(seller), items.load(item.itemInstanceId()).location());
        assertEquals(1, states.load(seller).stateVersion());
        assertArrayEquals(new byte[]{9}, states.load(seller).statePayload());
    }

    @Test
    void insufficientBuyerFundsLeaveListingAndAllAuthoritiesUntouched() throws SQLException {
        UUID seller = createPlayer("PoorSaleSeller");
        UUID buyer = createPlayer("PoorBuyer");
        AuctionListingCreateResult listing = createActiveListing(seller, 5_000);
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 4_999, "test.seed_buyer");
        long ledgerBefore = count("SELECT COUNT(*) FROM economic_ledger");
        long operationsBefore = count("SELECT COUNT(*) FROM processed_operations");

        assertThrows(
                AuctionHouseException.class,
                () -> auctions.purchase(
                        UUID.randomUUID(),
                        listing.listingId(),
                        buyer,
                        "auction.purchase"
                )
        );

        assertEquals(AuctionListingStatus.ACTIVE, auctions.load(listing.listingId()).status());
        assertEquals(ItemLocation.auctionEscrow(listing.listingId()), items.load(listing.itemInstanceId()).location());
        assertEquals(4_999, wallets.load(buyer).balanceMinor());
        assertEquals(0, wallets.load(seller).balanceMinor());
        assertEquals(ledgerBefore, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(operationsBefore, count("SELECT COUNT(*) FROM processed_operations"));
        assertEquals(0, count("SELECT COUNT(*) FROM pending_unique_deliveries"));
    }

    @Test
    void sellerCannotBuyOwnListingAndOtherPlayerCannotCancelIt() throws SQLException {
        UUID seller = createPlayer("AuthSeller");
        UUID other = createPlayer("AuthOther");
        AuctionListingCreateResult listing = createActiveListing(seller, 100);
        wallets.creditFromSystem(UUID.randomUUID(), seller, 1_000, "test.seed_seller");

        assertThrows(
                AuctionHouseException.class,
                () -> auctions.purchase(UUID.randomUUID(), listing.listingId(), seller, "auction.purchase")
        );
        assertThrows(
                AuctionHouseException.class,
                () -> auctions.cancel(UUID.randomUUID(), listing.listingId(), other, "auction.cancel")
        );
        assertEquals(AuctionListingStatus.ACTIVE, auctions.load(listing.listingId()).status());
    }

    private AuctionListingCreateResult createActiveListing(UUID seller, long priceMinor) throws SQLException {
        UniqueItemAuthorityResult item = createSword(seller);
        SessionLease lease = sessions.openSession(seller, "paper-a", null, LEASE);
        return auctions.createListing(
                UUID.randomUUID(),
                lease.sessionId(),
                "paper-a",
                0,
                item.itemInstanceId(),
                0,
                priceMinor,
                "city",
                null,
                new byte[]{8},
                "auction.list"
        );
    }

    private UniqueItemAuthorityResult createSword(UUID owner) throws SQLException {
        return items.createForPlayer(
                UUID.randomUUID(),
                "equipment.auction_sword",
                owner,
                "test.create",
                owner
        );
    }

    private UUID createPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            results.next();
            return results.getLong(1);
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }
}
