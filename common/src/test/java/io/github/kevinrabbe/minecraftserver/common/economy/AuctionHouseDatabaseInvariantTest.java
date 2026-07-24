package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemLocation;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class AuctionHouseDatabaseInvariantTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private UniqueItemAuthorityRepository items;
    private AuctionHouseRepository auctions;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                5
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        sessions = new PlayerSessionRepository(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                "equipment.auction_sword",
                "IRON_SWORD",
                "Auction Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
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
    void genericItemMoveCannotDetachActiveAuctionEscrow() throws SQLException {
        UUID seller = createPlayer("EscrowSeller");
        AuctionListingCreateResult listing = createActiveListing(seller);

        assertThrows(
                SQLException.class,
                () -> items.move(
                        UUID.randomUUID(),
                        listing.itemInstanceId(),
                        listing.escrowItemVersion(),
                        ItemLocation.auctionEscrow(listing.listingId()),
                        ItemLocation.quarantine(),
                        "test.illegal_detach",
                        null
                )
        );

        assertEquals(AuctionListingStatus.ACTIVE, auctions.load(listing.listingId()).status());
        assertEquals(
                ItemLocation.auctionEscrow(listing.listingId()),
                items.load(listing.itemInstanceId()).location()
        );
    }

    @Test
    void directSqlCannotMarkListingSoldWithoutMatchingPendingDeliveryAndItemMove() throws SQLException {
        UUID seller = createPlayer("SqlSeller");
        UUID buyer = createPlayer("SqlBuyer");
        AuctionListingCreateResult listing = createActiveListing(seller);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID fakeDelivery = UUID.randomUUID();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE auction_listings
                    SET status = 'SOLD',
                        settle_operation_id = ?,
                        buyer_player_id = ?,
                        settlement_delivery_id = ?,
                        settled_at = NOW()
                    WHERE listing_id = ?
                    """)) {
                update.setObject(1, UUID.randomUUID());
                update.setObject(2, buyer);
                update.setObject(3, fakeDelivery);
                update.setObject(4, listing.listingId());
                assertEquals(1, update.executeUpdate());
            }

            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }

        assertEquals(AuctionListingStatus.ACTIVE, auctions.load(listing.listingId()).status());
        assertEquals(
                ItemLocation.auctionEscrow(listing.listingId()),
                items.load(listing.itemInstanceId()).location()
        );
    }

    private AuctionListingCreateResult createActiveListing(UUID seller) throws SQLException {
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(),
                "equipment.auction_sword",
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
                100,
                "city",
                null,
                new byte[]{1},
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
