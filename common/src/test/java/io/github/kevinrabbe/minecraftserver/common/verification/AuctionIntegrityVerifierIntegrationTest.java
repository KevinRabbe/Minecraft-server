package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.AuctionHouseRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.AuctionListingCreateResult;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
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
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class AuctionIntegrityVerifierIntegrationTest {
    private static final String ITEM = "equipment.integrity_auction_sword";
    private static final String LIST_REASON = "auction.integrity_list";
    private static final Duration LEASE = Duration.ofMinutes(5);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private UniqueItemAuthorityRepository items;
    private CoinWalletRepository wallets;
    private AuctionHouseRepository auctions;
    private AuctionIntegrityVerifier verifier;

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
                ITEM,
                "IRON_SWORD",
                "Integrity Auction Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        wallets = new CoinWalletRepository(dataSource);
        auctions = new AuctionHouseRepository(dataSource, catalog);
        verifier = new AuctionIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        truncateAuctionAuthority();
    }

    @AfterEach
    void cleanDatabase() throws SQLException {
        truncateAuctionAuthority();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void realActiveSoldAndCancelledListingsRemainHistoricallyClean() throws Exception {
        UUID activeSeller = player("AhActiveSell");
        createActiveListing(activeSeller, 1_000L);

        UUID soldSeller = player("AhSoldSell");
        UUID buyer = player("AhSoldBuy");
        AuctionListingCreateResult sold = createActiveListing(soldSeller, 2_500L);
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 10_000L, "test.auction_integrity_seed");
        auctions.purchase(UUID.randomUUID(), sold.listingId(), buyer, "auction.integrity_purchase");

        UUID cancelSeller = player("AhCancelSell");
        AuctionListingCreateResult cancelled = createActiveListing(cancelSeller, 3_000L);
        auctions.cancel(UUID.randomUUID(), cancelled.listingId(), cancelSeller, "auction.integrity_cancel");

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void lostListingCreateOperationEvidenceIsDetected() throws Exception {
        UUID seller = player("AhCreateLoss");
        AuctionListingCreateResult listing = createActiveListing(seller, 1_500L);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE processed_operations");
        }

        assertContainsOnly("AUCTION_CREATE_EVIDENCE_MISMATCH", listing.listingId());
    }

    @Test
    void lostPurchaseLedgerEvidenceIsDetectedWithoutBreakingCreateEvidence() throws Exception {
        UUID seller = player("AhSettleSell");
        UUID buyer = player("AhSettleBuy");
        AuctionListingCreateResult listing = createActiveListing(seller, 2_000L);
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 10_000L, "test.auction_integrity_seed");
        auctions.purchase(UUID.randomUUID(), listing.listingId(), buyer, "auction.integrity_purchase");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement truncate = connection.createStatement()) {
                truncate.execute("TRUNCATE TABLE economic_ledger");
            }
            try (PreparedStatement restoreCreateLedger = connection.prepareStatement("""
                    INSERT INTO economic_ledger(
                        operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                    ) VALUES (?, 0, ?, 'ITEM_INSTANCE', ?, 1, 'DEBIT', ?)
                    """)) {
                restoreCreateLedger.setObject(1, listing.createOperationId());
                restoreCreateLedger.setObject(2, seller);
                restoreCreateLedger.setString(3, listing.itemInstanceId().toString());
                restoreCreateLedger.setString(4, LIST_REASON);
                assertEquals(1, restoreCreateLedger.executeUpdate());
            }
            connection.commit();
        }

        assertContainsOnly("AUCTION_SETTLEMENT_EVIDENCE_MISMATCH", listing.listingId());
    }

    private AuctionListingCreateResult createActiveListing(UUID seller, long priceMinor) throws SQLException {
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(),
                ITEM,
                seller,
                "test.auction_integrity_create",
                seller
        );
        String backend = "auction-integrity-" + UUID.randomUUID().toString().substring(0, 8);
        SessionLease lease = sessions.openSession(seller, backend, null, LEASE);
        return auctions.createListing(
                UUID.randomUUID(),
                lease.sessionId(),
                backend,
                lease.stateVersion(),
                item.itemInstanceId(),
                item.item().stateVersion(),
                priceMinor,
                "city",
                "auction-house",
                new byte[]{8},
                LIST_REASON
        );
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void assertContainsOnly(String expectedCode, UUID expectedSubject) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals(expectedCode, issue.code());
        assertEquals(expectedSubject.toString(), issue.subjectId());
    }

    private void truncateAuctionAuthority() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        auction_listings,
                        pending_unique_deliveries,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
                        player_sessions,
                        player_state,
                        player_names,
                        bank_accounts,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
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
