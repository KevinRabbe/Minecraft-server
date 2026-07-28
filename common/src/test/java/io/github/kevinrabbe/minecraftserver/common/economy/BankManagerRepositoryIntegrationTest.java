package io.github.kevinrabbe.minecraftserver.common.economy;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BankManagerRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;
    private BankManagerRepository banks;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                8
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        wallets = new CoinWalletRepository(dataSource);
        banks = new BankManagerRepository(dataSource, new BankTierCatalog(List.of(
                new BankTierDefinition(0, 100_000L, 0L, 5),
                new BankTierDefinition(1, 1_000_000L, 50_000L, 30),
                new BankTierDefinition(2, 100_000_000L, 1_000_000L, 30)
        )));
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE players RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void depositAndWithdrawMoveCoinsWithoutCreatingValue() throws SQLException {
        UUID playerId = newPlayer("Banker");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 200_000L, "test.seed");

        BankTransferResult deposit = banks.deposit(
                UUID.randomUUID(), playerId, 80_000L, "bank.deposit"
        );
        assertEquals(120_000L, deposit.walletBalanceMinor());
        assertEquals(80_000L, deposit.bankBalanceMinor());
        assertEquals(200_000L, deposit.walletBalanceMinor() + deposit.bankBalanceMinor());

        BankTransferResult withdraw = banks.withdraw(
                UUID.randomUUID(), playerId, 30_000L, "bank.withdraw"
        );
        assertEquals(150_000L, withdraw.walletBalanceMinor());
        assertEquals(50_000L, withdraw.bankBalanceMinor());
        assertEquals(200_000L, withdraw.walletBalanceMinor() + withdraw.bankBalanceMinor());
    }

    @Test
    void depositCannotExceedCurrentTierCapacityAndRollsBackWallet() throws SQLException {
        UUID playerId = newPlayer("Capacity");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 150_000L, "test.seed");

        assertThrows(
                BankManagerException.class,
                () -> banks.deposit(UUID.randomUUID(), playerId, 100_001L, "bank.deposit")
        );

        assertEquals(150_000L, wallets.load(playerId).balanceMinor());
        assertEquals(0L, banks.load(playerId).balanceMinor());
    }

    @Test
    void sameDepositOperationIsIdempotent() throws SQLException {
        UUID playerId = newPlayer("Retry");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 100_000L, "test.seed");
        UUID operationId = UUID.randomUUID();

        BankTransferResult first = banks.deposit(operationId, playerId, 25_000L, "bank.deposit");
        BankTransferResult retry = banks.deposit(operationId, playerId, 25_000L, "bank.deposit");

        assertEquals(first, retry);
        assertEquals(75_000L, wallets.load(playerId).balanceMinor());
        assertEquals(25_000L, banks.load(playerId).balanceMinor());
    }

    @Test
    void tierUpgradeDestroysConfiguredWalletCostAndRaisesCapacity() throws SQLException {
        UUID playerId = newPlayer("Upgrade");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 200_000L, "test.seed");
        banks.deposit(UUID.randomUUID(), playerId, 100_000L, "bank.deposit");

        BankUpgradeResult result = banks.upgrade(UUID.randomUUID(), playerId, "bank.upgrade");

        assertEquals(0, result.previousTier());
        assertEquals(1, result.newTier());
        assertEquals(50_000L, result.costMinor());
        assertEquals(50_000L, result.walletBalanceMinor());
        assertEquals(100_000L, result.bankBalanceMinor());
        assertEquals(1, banks.load(playerId).tier());

        banks.deposit(UUID.randomUUID(), playerId, 25_000L, "bank.deposit");
        assertEquals(125_000L, banks.load(playerId).balanceMinor());
    }

    @Test
    void interestCreditsConfiguredBasisPointsOncePerPeriod() throws SQLException {
        UUID playerId = newPlayer("Interest");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 200_000L, "test.seed");
        banks.deposit(UUID.randomUUID(), playerId, 100_000L, "bank.deposit");
        banks.upgrade(UUID.randomUUID(), playerId, "bank.upgrade");

        LocalDate period = LocalDate.of(2026, 7, 25);
        UUID operationId = UUID.randomUUID();
        BankInterestResult first = banks.creditDailyInterest(
                operationId, playerId, period, "bank.interest"
        );
        BankInterestResult retry = banks.creditDailyInterest(
                operationId, playerId, period, "bank.interest"
        );

        assertEquals(first, retry);
        assertEquals(300L, first.creditedMinor());
        assertEquals(100_300L, first.bankBalanceMinor());
        assertThrows(
                BankManagerException.class,
                () -> banks.creditDailyInterest(UUID.randomUUID(), playerId, period, "bank.interest")
        );
    }

    @Test
    void interestNeverPushesBalancePastCapacity() throws SQLException {
        UUID playerId = newPlayer("InterestCap");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 1_100_000L, "test.seed");
        banks.upgrade(UUID.randomUUID(), playerId, "bank.upgrade");
        banks.deposit(UUID.randomUUID(), playerId, 1_000_000L, "bank.deposit");

        BankInterestResult result = banks.creditDailyInterest(
                UUID.randomUUID(),
                playerId,
                LocalDate.of(2026, 7, 25),
                "bank.interest"
        );

        assertEquals(0L, result.creditedMinor());
        assertEquals(1_000_000L, result.bankBalanceMinor());
    }

    @Test
    void concurrentDepositsCannotOverspendWalletOrBankCapacity() throws Exception {
        UUID playerId = newPlayer("Concurrent");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 100_000L, "test.seed");

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> tasks = java.util.stream.IntStream.range(0, 20)
                    .<Callable<Boolean>>mapToObj(index -> () -> {
                        try {
                            banks.deposit(UUID.randomUUID(), playerId, 10_000L, "bank.deposit");
                            return true;
                        } catch (BankManagerException exception) {
                            return false;
                        }
                    })
                    .toList();
            List<Future<Boolean>> futures = executor.invokeAll(tasks);
            long successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    successes++;
                }
            }
            assertEquals(10L, successes);
        }

        assertEquals(0L, wallets.load(playerId).balanceMinor());
        assertEquals(100_000L, banks.load(playerId).balanceMinor());
    }

    private UUID newPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
