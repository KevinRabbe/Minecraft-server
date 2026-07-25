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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class AuctionHouseQueryRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String SWORD = "equipment.auction_query_sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private UniqueItemAuthorityRepository items;
    private AuctionHouseRepository auctions;
    private AuctionHouseQueryRepository queries;

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
                SWORD,
                "IRON_SWORD",
                "Auction Query Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        auctions = new AuctionHouseRepository(dataSource, catalog);
        queries = new AuctionHouseQueryRepository(dataSource);
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
    void browseReturnsOnlyActiveListingsWithStableProjectionFields() throws SQLException {
        UUID cancelledSeller = createPlayer("QueryCancel");
        UUID activeSeller = createPlayer("QueryActive");
        AuctionListingCreateResult cancelled = createActiveListing(cancelledSeller, 1_250);
        AuctionListingCreateResult active = createActiveListing(activeSeller, 4_500);

        auctions.cancel(UUID.randomUUID(), cancelled.listingId(), cancelledSeller, "auction.cancel");

        List<AuctionBrowseListing> listings = queries.listActive(100);

        assertEquals(1, listings.size());
        AuctionBrowseListing visible = listings.getFirst();
        assertEquals(active.listingId(), visible.listingId());
        assertEquals(activeSeller, visible.sellerPlayerId());
        assertEquals(active.itemInstanceId(), visible.itemInstanceId());
        assertEquals(SWORD, visible.definitionId());
        assertEquals(4_500, visible.priceMinor());
        assertEquals(java.util.Map.of(), visible.rollQualityBasisPoints());
    }

    @Test
    void browseRejectsUnboundedLimitsBeforeQuerying() {
        assertThrows(IllegalArgumentException.class, () -> queries.listActive(0));
        assertThrows(IllegalArgumentException.class, () -> queries.listActive(101));
    }

    private AuctionListingCreateResult createActiveListing(UUID seller, long priceMinor) throws SQLException {
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(),
                SWORD,
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
                priceMinor,
                "city",
                "auction-house",
                new byte[]{8},
                "auction.list"
        );
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
