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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PveDeathLossRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;
    private BankManagerRepository banks;
    private PveDeathLossRepository deathLoss;

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
                new BankTierDefinition(0, 1_000_000L, 0L, 0),
                new BankTierDefinition(1, 10_000_000L, 0L, 0)
        )));
        deathLoss = new PveDeathLossRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE players RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void evaluatesPolicyAgainstLockedPocketBalanceAndLeavesProtectedBankUntouched() throws Exception {
        UUID playerId = newPlayer("DeathBank");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 100_000L, "test.seed");
        banks.deposit(UUID.randomUUID(), playerId, 40_000L, "bank.deposit");
        CoinWalletSnapshot before = wallets.load(playerId);
        BankAccountSnapshot protectedBefore = banks.load(playerId);

        PveDeathLossResult result = deathLoss.apply(
                UUID.randomUUID(),
                playerId,
                "test.quarter_v1",
                lockedBalance -> lockedBalance / 4,
                "pve.death"
        );

        assertEquals(before.balanceMinor(), result.previousBalanceMinor());
        assertEquals(before.stateVersion(), result.previousWalletStateVersion());
        assertEquals(15_000L, result.lossMinor());
        assertEquals(45_000L, result.walletBalanceMinor());
        assertEquals(before.stateVersion() + 1, result.walletStateVersion());
        assertEquals(result.walletBalanceMinor(), wallets.load(playerId).balanceMinor());
        assertEquals(protectedBefore, banks.load(playerId));
        assertEquals(15_000L, ledgerAmount(result.playerId(), "pve.death"));
    }

    @Test
    void replayReturnsFrozenResultWithoutReevaluatingPolicyOrDestroyingAgain() throws Exception {
        UUID playerId = newPlayer("DeathReplay");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 80_000L, "test.seed");
        UUID operationId = UUID.randomUUID();
        AtomicInteger evaluations = new AtomicInteger();
        PveDeathLossPolicy policy = balance -> {
            evaluations.incrementAndGet();
            return balance / 5;
        };

        PveDeathLossResult first = deathLoss.apply(
                operationId, playerId, "test.fifth_v1", policy, "pve.death"
        );
        PveDeathLossResult retry = deathLoss.apply(
                operationId, playerId, "test.fifth_v1", policy, "pve.death"
        );

        assertEquals(first, retry);
        assertEquals(1, evaluations.get());
        assertEquals(64_000L, wallets.load(playerId).balanceMinor());
        assertEquals(1L, countProcessed(operationId));
        assertEquals(1L, countLedger(operationId));
    }

    @Test
    void zeroLossStillCommitsDeathOutcomeWithoutAdvancingWalletOrCreatingLedger() throws Exception {
        UUID playerId = newPlayer("DeathZero");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 50_000L, "test.seed");
        CoinWalletSnapshot before = wallets.load(playerId);
        UUID operationId = UUID.randomUUID();

        PveDeathLossResult result = deathLoss.apply(
                operationId, playerId, "test.zero_v1", balance -> 0L, "pve.death"
        );

        assertEquals(0L, result.lossMinor());
        assertEquals(before.balanceMinor(), result.walletBalanceMinor());
        assertEquals(before.stateVersion(), result.walletStateVersion());
        assertEquals(before, wallets.load(playerId));
        assertEquals(1L, countProcessed(operationId));
        assertEquals(0L, countLedger(operationId));
    }

    @Test
    void invalidPolicyResultRollsBackWithoutEconomicEvidence() throws Exception {
        UUID playerId = newPlayer("DeathInvalid");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 25_000L, "test.seed");
        CoinWalletSnapshot before = wallets.load(playerId);
        UUID operationId = UUID.randomUUID();

        assertThrows(
                CoinWalletException.class,
                () -> deathLoss.apply(
                        operationId,
                        playerId,
                        "test.invalid_v1",
                        balance -> balance + 1,
                        "pve.death"
                )
        );

        assertEquals(before, wallets.load(playerId));
        assertEquals(0L, countProcessed(operationId));
        assertEquals(0L, countLedger(operationId));
    }

    @Test
    void concurrentReplayEvaluatesAndDestroysExactlyOnce() throws Exception {
        UUID playerId = newPlayer("DeathRace");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 100_000L, "test.seed");
        UUID operationId = UUID.randomUUID();
        AtomicInteger evaluations = new AtomicInteger();
        PveDeathLossPolicy policy = balance -> {
            evaluations.incrementAndGet();
            return balance / 2;
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<PveDeathLossResult>> tasks = java.util.stream.IntStream.range(0, 16)
                    .<Callable<PveDeathLossResult>>mapToObj(index -> () -> deathLoss.apply(
                            operationId, playerId, "test.half_v1", policy, "pve.death"
                    ))
                    .toList();
            List<Future<PveDeathLossResult>> futures = executor.invokeAll(tasks);
            PveDeathLossResult expected = futures.getFirst().get();
            for (Future<PveDeathLossResult> future : futures) {
                assertEquals(expected, future.get());
            }
        }

        assertEquals(1, evaluations.get());
        assertEquals(50_000L, wallets.load(playerId).balanceMinor());
        assertEquals(1L, countProcessed(operationId));
        assertEquals(1L, countLedger(operationId));
    }

    @Test
    void distinctConcurrentDeathsSerializeOnCurrentLockedBalance() throws Exception {
        UUID playerId = newPlayer("DeathSerial");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 100_000L, "test.seed");
        PveDeathLossPolicy half = balance -> balance / 2;

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<PveDeathLossResult> first = executor.submit(() -> deathLoss.apply(
                    UUID.randomUUID(), playerId, "test.half_v1", half, "pve.death"
            ));
            Future<PveDeathLossResult> second = executor.submit(() -> deathLoss.apply(
                    UUID.randomUUID(), playerId, "test.half_v1", half, "pve.death"
            ));
            long totalDestroyed = first.get().lossMinor() + second.get().lossMinor();
            assertEquals(75_000L, totalDestroyed);
        }

        assertEquals(25_000L, wallets.load(playerId).balanceMinor());
    }

    @Test
    void replayRejectsDifferentPolicyVersionOrPlayer() throws Exception {
        UUID playerId = newPlayer("DeathIdentity");
        UUID otherPlayerId = newPlayer("DeathOther");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 20_000L, "test.seed");
        UUID operationId = UUID.randomUUID();
        deathLoss.apply(operationId, playerId, "test.policy_v1", balance -> 1_000L, "pve.death");

        assertThrows(
                CoinWalletException.class,
                () -> deathLoss.apply(operationId, playerId, "test.policy_v2", balance -> 2_000L, "pve.death")
        );
        assertThrows(
                CoinWalletException.class,
                () -> deathLoss.apply(operationId, otherPlayerId, "test.policy_v1", balance -> 1_000L, "pve.death")
        );
    }

    private UUID newPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private long countProcessed(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM processed_operations
                     WHERE operation_id = ? AND operation_type = 'PVE_DEATH_LOSS'
                     """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long countLedger(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM economic_ledger
                     WHERE operation_id = ?
                     """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long ledgerAmount(UUID playerId, String reason) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COALESCE(SUM(amount), 0)
                     FROM economic_ledger
                     WHERE player_id = ?
                       AND reason = ?
                       AND asset_type = ?
                       AND asset_id = ?
                       AND direction = 'DEBIT'
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, reason);
            statement.setString(3, CoinCurrency.LEDGER_ASSET_TYPE);
            statement.setString(4, CoinCurrency.LEDGER_ASSET_ID);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
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
