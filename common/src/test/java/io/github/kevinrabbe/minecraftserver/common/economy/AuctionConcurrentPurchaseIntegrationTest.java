package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityResult;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class AuctionConcurrentPurchaseIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private UniqueItemAuthorityRepository items;
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

        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                "equipment.race_sword",
                "IRON_SWORD",
                "Race Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
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
    void exactlyOneBuyerSettlesConcurrentPurchaseRace() throws Exception {
        UUID seller = createPlayer("RaceSeller");
        UUID buyerA = createPlayer("RaceBuyerA");
        UUID buyerB = createPlayer("RaceBuyerB");
        long price = 2_000;
        long startingBuyerBalance = 5_000;
        AuctionListingCreateResult listing = createListing(seller, price);
        wallets.creditFromSystem(UUID.randomUUID(), buyerA, startingBuyerBalance, "test.seed_a");
        wallets.creditFromSystem(UUID.randomUUID(), buyerB, startingBuyerBalance, "test.seed_b");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AuctionPurchaseResult> futureA = executor.submit(
                    () -> purchaseAfterLatch(ready, start, listing.listingId(), buyerA)
            );
            Future<AuctionPurchaseResult> futureB = executor.submit(
                    () -> purchaseAfterLatch(ready, start, listing.listingId(), buyerB)
            );

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            AuctionPurchaseResult winner = null;
            Throwable loserFailure = null;
            try {
                winner = futureA.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                loserFailure = exception.getCause();
            }
            try {
                AuctionPurchaseResult resultB = futureB.get(10, TimeUnit.SECONDS);
                if (winner == null) {
                    winner = resultB;
                } else {
                    throw new AssertionError("Both buyers settled the same listing");
                }
            } catch (ExecutionException exception) {
                if (loserFailure != null) {
                    throw new AssertionError("Both buyers failed the listing race", exception);
                }
                loserFailure = exception.getCause();
            }

            assertNotNull(winner);
            assertInstanceOf(AuctionHouseException.class, loserFailure);
            assertEquals(AuctionListingStatus.SOLD, auctions.load(listing.listingId()).status());
            assertEquals(winner.buyerPlayerId(), auctions.load(listing.listingId()).buyerPlayerId());
            assertEquals(price, wallets.load(seller).balanceMinor());
            assertEquals(
                    startingBuyerBalance * 2 - price,
                    wallets.load(buyerA).balanceMinor() + wallets.load(buyerB).balanceMinor()
            );
            assertEquals(1, pendingDeliveryCountForItem(listing.itemInstanceId()));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private AuctionPurchaseResult purchaseAfterLatch(
            CountDownLatch ready,
            CountDownLatch start,
            UUID listingId,
            UUID buyer
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to start auction purchase race");
        }
        return auctions.purchase(UUID.randomUUID(), listingId, buyer, "auction.purchase");
    }

    private AuctionListingCreateResult createListing(UUID seller, long price) throws SQLException {
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(),
                "equipment.race_sword",
                seller,
                "test.create",
                seller
        );
        SessionLease lease = sessions.openSession(seller, "paper-a", null, LEASE);
        return auctions.createListing(
                UUID.randomUUID(),
                lease.sessionId(),
                "paper-a",
                0,
                item.itemInstanceId(),
                0,
                price,
                "city",
                null,
                new byte[]{1},
                "auction.list"
        );
    }

    private long pendingDeliveryCountForItem(UUID itemInstanceId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM pending_unique_deliveries
                     WHERE item_instance_id = ? AND status = 'PENDING'
                     """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getLong(1);
            }
        }
    }

    private UUID createPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }
}
