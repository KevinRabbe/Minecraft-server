package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryClaimResult;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryIssueResult;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class AuctionHistoricalRelistingIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PendingUniqueDeliveryRepository deliveries;
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
                "equipment.relist_sword",
                "IRON_SWORD",
                "Relist Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        deliveries = new PendingUniqueDeliveryRepository(dataSource, catalog);
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
    void oneItemCanHaveManyHistoricalListingsButOnlyOneActiveListing() throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "RelistOwner");
        PendingUniqueDeliveryIssueResult issued = deliveries.issueNewIndividual(
                UUID.randomUUID(),
                "equipment.relist_sword",
                playerId,
                "test.issue",
                playerId
        );
        SessionLease lease = sessions.openSession(playerId, "paper-a", null, LEASE);

        PendingUniqueDeliveryClaimResult firstClaim = deliveries.claimToPlayerState(
                UUID.randomUUID(),
                issued.deliveryId(),
                lease.sessionId(),
                "paper-a",
                0,
                "city",
                null,
                new byte[]{1},
                "test.claim"
        );

        AuctionListingCreateResult firstListing = auctions.createListing(
                UUID.randomUUID(),
                lease.sessionId(),
                "paper-a",
                firstClaim.playerStateVersion(),
                issued.itemInstanceId(),
                firstClaim.itemStateVersion(),
                1_000,
                "city",
                null,
                new byte[]{2},
                "auction.list"
        );
        AuctionCancelResult cancelled = auctions.cancel(
                UUID.randomUUID(),
                firstListing.listingId(),
                playerId,
                "auction.cancel"
        );

        PendingUniqueDeliveryClaimResult secondClaim = deliveries.claimToPlayerState(
                UUID.randomUUID(),
                cancelled.deliveryId(),
                lease.sessionId(),
                "paper-a",
                firstListing.playerStateVersion(),
                "city",
                null,
                new byte[]{3},
                "test.claim_again"
        );

        AuctionListingCreateResult secondListing = auctions.createListing(
                UUID.randomUUID(),
                lease.sessionId(),
                "paper-a",
                secondClaim.playerStateVersion(),
                issued.itemInstanceId(),
                secondClaim.itemStateVersion(),
                1_500,
                "city",
                null,
                new byte[]{4},
                "auction.relist"
        );

        assertEquals(AuctionListingStatus.CANCELLED, auctions.load(firstListing.listingId()).status());
        assertEquals(AuctionListingStatus.ACTIVE, auctions.load(secondListing.listingId()).status());
        assertEquals(2, listingCountForItem(issued.itemInstanceId()));
        assertEquals(1, activeListingCountForItem(issued.itemInstanceId()));
        assertEquals(2, deliveryCountForItem(issued.itemInstanceId()));
    }

    private long listingCountForItem(UUID itemInstanceId) throws SQLException {
        return countByItem("SELECT COUNT(*) FROM auction_listings WHERE item_instance_id = ?", itemInstanceId);
    }

    private long activeListingCountForItem(UUID itemInstanceId) throws SQLException {
        return countByItem(
                "SELECT COUNT(*) FROM auction_listings WHERE item_instance_id = ? AND status = 'ACTIVE'",
                itemInstanceId
        );
    }

    private long deliveryCountForItem(UUID itemInstanceId) throws SQLException {
        return countByItem(
                "SELECT COUNT(*) FROM pending_unique_deliveries WHERE item_instance_id = ?",
                itemInstanceId
        );
    }

    private long countByItem(String sql, UUID itemInstanceId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getLong(1);
            }
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
