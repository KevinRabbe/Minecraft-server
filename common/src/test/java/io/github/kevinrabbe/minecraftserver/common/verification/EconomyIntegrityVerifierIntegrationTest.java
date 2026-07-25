package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.BankManagerRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierCatalog;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierDefinition;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeSnapshot;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryIssueResult;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryRepository;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class EconomyIntegrityVerifierIntegrationTest {
    private static final String UNIQUE = "verify.sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;
    private BankManagerRepository bank;
    private SecureTradeRepository trades;
    private PendingUniqueDeliveryRepository uniqueDeliveries;
    private EconomyIntegrityVerifier verifier;

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
        wallets = new CoinWalletRepository(dataSource);
        bank = new BankManagerRepository(
                dataSource,
                new BankTierCatalog(List.of(
                        new BankTierDefinition(0, 1_000_000, 0)
                ))
        );
        trades = new SecureTradeRepository(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        UNIQUE,
                        "IRON_SWORD",
                        "Verifier Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                )
        ));
        uniqueDeliveries = new PendingUniqueDeliveryRepository(dataSource, catalog);
        verifier = new EconomyIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        salvage_records,
                        secure_trade_deliveries,
                        secure_trade_unique_items,
                        secure_trade_commodity_escrow,
                        secure_trade_coin_escrow,
                        secure_trades,
                        auction_listings,
                        pending_unique_deliveries,
                        pending_commodity_deliveries,
                        item_provenance,
                        item_instances,
                        crafting_commission_returns,
                        crafting_commission_materials,
                        crafting_commissions,
                        bazaar_fills,
                        bazaar_orders,
                        bank_interest_credits,
                        economic_ledger,
                        processed_operations,
                        bank_accounts,
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void cleanCrossSystemCoinMovementAndCustodyProducesNoIssues() throws Exception {
        UUID playerA = identities.ensurePlayer(UUID.randomUUID(), "VerifyA");
        UUID playerB = identities.ensurePlayer(UUID.randomUUID(), "VerifyB");
        wallets.creditFromSystem(UUID.randomUUID(), playerA, 10_000, "test.funding");
        wallets.creditFromSystem(UUID.randomUUID(), playerB, 2_000, "test.funding");

        bank.deposit(UUID.randomUUID(), playerA, 3_000, "bank.deposit");
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), playerA, playerB);
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), playerA, 1_000, "trade.coin_offer");

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void schemaValidWalletDriftIsReportedAsCriticalCoinMismatch() throws Exception {
        UUID player = identities.ensurePlayer(UUID.randomUUID(), "VerifyCoin");
        wallets.creditFromSystem(UUID.randomUUID(), player, 1_000, "test.funding");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE wallets
                     SET balance_minor = balance_minor + 1,
                         state_version = state_version + 1
                     WHERE player_id = ?
                     """)) {
            statement.setObject(1, player);
            assertEquals(1, statement.executeUpdate());
        }

        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size());
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("COIN_HOLDINGS_LEDGER_MISMATCH", issue.code());
        assertEquals(player.toString(), issue.subjectId());
    }

    @Test
    void pendingUniqueCustodyDriftIsReportedWithoutRepairingState() throws Exception {
        UUID player = identities.ensurePlayer(UUID.randomUUID(), "VerifyItem");
        PendingUniqueDeliveryIssueResult issued = uniqueDeliveries.issueNewIndividual(
                UUID.randomUUID(), UNIQUE, player, "test.delivery", player
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE item_instances
                     SET location_kind = 'QUARANTINE',
                         location_id = NULL,
                         state_version = state_version + 1,
                         updated_at = NOW()
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, issued.itemInstanceId());
            assertEquals(1, statement.executeUpdate());
        }

        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size());
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("PENDING_UNIQUE_CUSTODY_MISMATCH", issue.code());
        assertEquals(issued.deliveryId().toString(), issue.subjectId());

        assertEquals("QUARANTINE", itemLocationKind(issued.itemInstanceId()));
    }

    @Test
    void maxIssuesBoundsDiagnosticOutputAcrossMultipleCorruptions() throws Exception {
        UUID first = identities.ensurePlayer(UUID.randomUUID(), "VerifyBoundA");
        UUID second = identities.ensurePlayer(UUID.randomUUID(), "VerifyBoundB");
        wallets.creditFromSystem(UUID.randomUUID(), first, 100, "test.funding");
        wallets.creditFromSystem(UUID.randomUUID(), second, 200, "test.funding");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE wallets
                     SET balance_minor = balance_minor + 1,
                         state_version = state_version + 1
                     WHERE player_id IN (?, ?)
                     """)) {
            statement.setObject(1, first);
            statement.setObject(2, second);
            assertEquals(2, statement.executeUpdate());
        }

        List<IntegrityIssue> issues = verifier.verify(1);
        assertEquals(1, issues.size());
        assertEquals("COIN_HOLDINGS_LEDGER_MISMATCH", issues.getFirst().code());
    }

    private String itemLocationKind(UUID itemId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind FROM item_instances WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new AssertionError("missing item");
                return row.getString(1);
            }
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
