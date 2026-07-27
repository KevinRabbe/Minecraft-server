package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.BankManagerRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierCatalog;
import io.github.kevinrabbe.minecraftserver.common.economy.BankTierDefinition;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BankIntegrityVerifierIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;
    private BankTierCatalog tiers;
    private BankManagerRepository banks;
    private BankIntegrityVerifier verifier;

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
        tiers = new BankTierCatalog(List.of(
                new BankTierDefinition(0, 100_000L, 0L, 0),
                new BankTierDefinition(1, 1_000_000L, 10_000L, 100)
        ));
        banks = new BankManagerRepository(dataSource, tiers);
        verifier = new BankIntegrityVerifier(dataSource, tiers);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        truncateBankAuthority();
    }

    @AfterEach
    void cleanDatabase() throws SQLException {
        truncateBankAuthority();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void realDepositWithdrawUpgradeAndInterestHistoryIsClean() throws Exception {
        UUID playerId = fundedPlayer("BankCheck", 250_000L);

        banks.deposit(UUID.randomUUID(), playerId, 80_000L, "bank.integrity_deposit");
        banks.withdraw(UUID.randomUUID(), playerId, 20_000L, "bank.integrity_withdraw");
        banks.upgrade(UUID.randomUUID(), playerId, "bank.integrity_upgrade");
        banks.creditDailyInterest(
                UUID.randomUUID(),
                playerId,
                LocalDate.of(2026, 7, 27),
                "bank.integrity_interest"
        );

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void lostProcessedBankHistoryIsDetectedEvenWhenAccountRowSurvives() throws Exception {
        UUID playerId = fundedPlayer("BankEvidenceA", 150_000L);
        banks.deposit(UUID.randomUUID(), playerId, 50_000L, "bank.integrity_deposit");
        truncateProcessedOperations();

        assertContainsOnly("BANK_STATE_EVIDENCE_MISMATCH", playerId.toString());
    }

    @Test
    void mutableTierCorruptionIsDetectedWithoutChangingCoinTotals() throws Exception {
        UUID playerId = fundedPlayer("BankEvidenceB", 150_000L);
        banks.deposit(UUID.randomUUID(), playerId, 50_000L, "bank.integrity_deposit");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE bank_accounts SET tier = 1 WHERE player_id = ?
                     """)) {
            statement.setObject(1, playerId);
            assertEquals(1, statement.executeUpdate());
        }

        assertContainsOnly("BANK_STATE_EVIDENCE_MISMATCH", playerId.toString());
    }

    @Test
    void missingBankLedgerEvidenceIsDetected() throws Exception {
        UUID playerId = fundedPlayer("BankEvidenceC", 150_000L);
        UUID operationId = UUID.randomUUID();
        banks.deposit(operationId, playerId, 50_000L, "bank.integrity_deposit");
        truncateEconomicLedger();

        assertContainsOnly("BANK_LEDGER_EVIDENCE_MISMATCH", operationId.toString());
    }

    @Test
    void currentTierMustExistInLoadedCatalog() throws Exception {
        UUID playerId = fundedPlayer("BankCatalog", 150_000L);
        banks.upgrade(UUID.randomUUID(), playerId, "bank.integrity_upgrade");

        BankTierCatalog reducedCatalog = new BankTierCatalog(List.of(
                new BankTierDefinition(0, 100_000L, 0L, 0)
        ));
        BankIntegrityVerifier reducedVerifier = new BankIntegrityVerifier(dataSource, reducedCatalog);
        List<IntegrityIssue> issues = reducedVerifier.verify(100);

        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("BANK_CATALOG_STATE_MISMATCH", issue.code());
        assertEquals(playerId.toString(), issue.subjectId());
    }

    private UUID fundedPlayer(String name, long amountMinor) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, amountMinor, "test.bank_integrity_seed");
        return playerId;
    }

    private void assertContainsOnly(String expectedCode, String expectedSubject) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals(expectedCode, issue.code());
        assertEquals(expectedSubject, issue.subjectId());
    }

    private void truncateProcessedOperations() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE processed_operations");
        }
    }

    private void truncateEconomicLedger() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE economic_ledger");
        }
    }

    private void truncateBankAuthority() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        economic_ledger,
                        processed_operations,
                        bank_accounts,
                        wallets,
                        player_names,
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
