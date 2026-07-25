package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
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
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityResult;
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
    private UniqueItemAuthorityRepository uniqueItems;
    private ClanMembershipRepository memberships;
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
                new BankTierCatalog(List.of(new BankTierDefinition(0, 1_000_000, 0)))
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
        uniqueItems = new UniqueItemAuthorityRepository(dataSource, catalog);
        memberships = new ClanMembershipRepository(dataSource);
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
                        clan_invitations,
                        clan_commodity_balances,
                        clan_treasuries,
                        clan_members,
                        clans,
                        item_provenance,
                        item_instances,
                        crafting_commission_returns,
                        crafting_commission_materials,
                        crafting_commissions,
                        bazaar_fills,
                        bazaar_orders,
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
    void validPendingUniqueCustodyProducesNoVerifierIssue() throws Exception {
        UUID player = identities.ensurePlayer(UUID.randomUUID(), "VerifyItem");
        PendingUniqueDeliveryIssueResult issued = uniqueDeliveries.issueNewIndividual(
                UUID.randomUUID(), UNIQUE, player, "test.delivery", player
        );

        assertEquals(player, issued.recipientPlayerId());
        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void schemaValidClanCommodityDriftIsReported() throws Exception {
        UUID leader = identities.ensurePlayer(UUID.randomUUID(), "VerifyClanA");
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "Verifier A", "VRA");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO clan_commodity_balances(clan_id, commodity_definition_id, quantity, state_version)
                     VALUES (?, 'verify.iron', 7, 1)
                     """)) {
            statement.setObject(1, clan.clanId());
            assertEquals(1, statement.executeUpdate());
        }

        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size());
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("CLAN_COMMODITY_LEDGER_MISMATCH", issue.code());
        assertEquals(clan.clanId() + ":verify.iron", issue.subjectId());
    }

    @Test
    void schemaValidClanUniqueCustodyWithoutClanLedgerEvidenceIsReported() throws Exception {
        UUID leader = identities.ensurePlayer(UUID.randomUUID(), "VerifyClanB");
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "Verifier B", "VRB");
        UniqueItemAuthorityResult item = uniqueItems.createForPlayer(
                UUID.randomUUID(), UNIQUE, leader, "test.item", leader
        );
        UUID corruptMoveOperation = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement provenance = connection.prepareStatement("""
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
                    ) VALUES (?, ?, ?, 'MOVED', 'PLAYER_INVENTORY', ?, 'CLAN_STORAGE', ?, 'test.corruption', ?)
                    """);
                 PreparedStatement update = connection.prepareStatement("""
                    UPDATE item_instances
                    SET location_kind = 'CLAN_STORAGE',
                        location_id = ?,
                        state_version = ?,
                        updated_at = NOW()
                    WHERE item_instance_id = ? AND state_version = ?
                    """)) {
                provenance.setObject(1, item.itemInstanceId());
                provenance.setLong(2, item.stateVersion() + 1);
                provenance.setObject(3, corruptMoveOperation);
                provenance.setObject(4, leader);
                provenance.setObject(5, clan.clanId());
                provenance.setObject(6, leader);
                provenance.executeUpdate();

                update.setObject(1, clan.clanId());
                update.setLong(2, item.stateVersion() + 1);
                update.setObject(3, item.itemInstanceId());
                update.setLong(4, item.stateVersion());
                assertEquals(1, update.executeUpdate());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }

        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size());
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("CLAN_UNIQUE_CUSTODY_LEDGER_MISMATCH", issue.code());
        assertEquals(clan.clanId() + ":" + item.itemInstanceId(), issue.subjectId());
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

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
