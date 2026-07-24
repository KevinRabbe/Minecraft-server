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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CoinWalletRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                4
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        wallets = new CoinWalletRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
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
    void stablePlayerIdentityAutomaticallyGetsZeroWallet() throws SQLException {
        UUID playerId = createPlayer("ZeroWallet");

        CoinWalletSnapshot wallet = wallets.load(playerId);

        assertEquals(0, wallet.balanceMinor());
        assertEquals(0, wallet.stateVersion());
        assertEquals(100L, CoinCurrency.MINOR_UNITS_PER_COIN);
    }

    @Test
    void systemCreditAndDebitAreIdempotentVersionedAndLedgered() throws SQLException {
        UUID playerId = createPlayer("MutationWallet");
        UUID creditOperation = UUID.randomUUID();

        CoinWalletMutationResult credited = wallets.creditFromSystem(
                creditOperation,
                playerId,
                12_345,
                "test.seed"
        );
        CoinWalletMutationResult creditRetry = wallets.creditFromSystem(
                creditOperation,
                playerId,
                12_345,
                "test.seed"
        );

        assertEquals(credited, creditRetry);
        assertEquals(12_345, credited.balanceMinor());
        assertEquals(1, credited.stateVersion());

        UUID debitOperation = UUID.randomUUID();
        CoinWalletMutationResult debited = wallets.debitToSystem(
                debitOperation,
                playerId,
                2_345,
                "test.sink"
        );
        CoinWalletMutationResult debitRetry = wallets.debitToSystem(
                debitOperation,
                playerId,
                2_345,
                "test.sink"
        );

        assertEquals(debited, debitRetry);
        assertEquals(10_000, debited.balanceMinor());
        assertEquals(2, debited.stateVersion());
        assertEquals(10_000, wallets.load(playerId).balanceMinor());
        assertEquals(2, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(2, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void playerTransferIsAtomicIdempotentAndConservesCoin() throws SQLException {
        UUID from = createPlayer("TransferFrom");
        UUID to = createPlayer("TransferTo");
        wallets.creditFromSystem(UUID.randomUUID(), from, 10_000, "test.seed");

        UUID operationId = UUID.randomUUID();
        CoinTransferResult first = wallets.transfer(
                operationId,
                from,
                to,
                2_500,
                "test.trade"
        );
        CoinTransferResult retry = wallets.transfer(
                operationId,
                from,
                to,
                2_500,
                "test.trade"
        );

        assertEquals(first, retry);
        assertEquals(7_500, first.fromBalanceMinor());
        assertEquals(2, first.fromStateVersion());
        assertEquals(2_500, first.toBalanceMinor());
        assertEquals(1, first.toStateVersion());
        assertEquals(10_000, wallets.load(from).balanceMinor() + wallets.load(to).balanceMinor());
        assertEquals(3, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(2, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void retryReturnsOriginalCommittedResultEvenAfterWalletsChangeAgain() throws SQLException {
        UUID playerA = createPlayer("RetryA");
        UUID playerB = createPlayer("RetryB");
        wallets.creditFromSystem(UUID.randomUUID(), playerA, 1_000, "test.seed");

        UUID firstOperation = UUID.randomUUID();
        CoinTransferResult original = wallets.transfer(
                firstOperation,
                playerA,
                playerB,
                400,
                "test.first"
        );
        wallets.transfer(
                UUID.randomUUID(),
                playerB,
                playerA,
                100,
                "test.second"
        );

        CoinTransferResult retry = wallets.transfer(
                firstOperation,
                playerA,
                playerB,
                400,
                "test.first"
        );

        assertEquals(original, retry);
        assertEquals(700, wallets.load(playerA).balanceMinor());
        assertEquals(300, wallets.load(playerB).balanceMinor());
    }

    @Test
    void insufficientFundsRollBackWithoutPartialLedgerOrOperationRecord() throws SQLException {
        UUID from = createPlayer("PoorFrom");
        UUID to = createPlayer("PoorTo");
        wallets.creditFromSystem(UUID.randomUUID(), from, 100, "test.seed");
        long ledgerBefore = count("SELECT COUNT(*) FROM economic_ledger");
        long operationsBefore = count("SELECT COUNT(*) FROM processed_operations");

        assertThrows(
                CoinWalletException.class,
                () -> wallets.transfer(
                        UUID.randomUUID(),
                        from,
                        to,
                        101,
                        "test.too_expensive"
                )
        );

        assertEquals(100, wallets.load(from).balanceMinor());
        assertEquals(0, wallets.load(to).balanceMinor());
        assertEquals(ledgerBefore, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(operationsBefore, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void operationIdCannotBeReusedForDifferentEconomicIntent() throws SQLException {
        UUID playerA = createPlayer("ReuseA");
        UUID playerB = createPlayer("ReuseB");
        UUID operationId = UUID.randomUUID();

        wallets.creditFromSystem(operationId, playerA, 500, "test.reward");

        assertThrows(
                CoinWalletException.class,
                () -> wallets.creditFromSystem(operationId, playerA, 501, "test.reward")
        );
        assertThrows(
                CoinWalletException.class,
                () -> wallets.transfer(operationId, playerA, playerB, 100, "test.trade")
        );
        assertEquals(500, wallets.load(playerA).balanceMinor());
        assertEquals(0, wallets.load(playerB).balanceMinor());
    }

    @Test
    void overflowFailsClosedAndDoesNotAppendEconomicEvidence() throws SQLException {
        UUID playerId = createPlayer("Overflow");
        setWallet(playerId, Long.MAX_VALUE - 5, 0);
        long ledgerBefore = count("SELECT COUNT(*) FROM economic_ledger");
        long operationsBefore = count("SELECT COUNT(*) FROM processed_operations");

        assertThrows(
                CoinWalletException.class,
                () -> wallets.creditFromSystem(
                        UUID.randomUUID(),
                        playerId,
                        6,
                        "test.overflow"
                )
        );

        assertEquals(Long.MAX_VALUE - 5, wallets.load(playerId).balanceMinor());
        assertEquals(ledgerBefore, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(operationsBefore, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void databaseRejectsNegativeBalanceEvenOutsideRepository() throws SQLException {
        UUID playerId = createPlayer("NegativeGuard");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE wallets
                     SET balance_minor = -1
                     WHERE player_id = ?
                     """)) {
            statement.setObject(1, playerId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private UUID createPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void setWallet(UUID playerId, long balanceMinor, long stateVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE wallets
                     SET balance_minor = ?, state_version = ?
                     WHERE player_id = ?
                     """)) {
            statement.setLong(1, balanceMinor);
            statement.setLong(2, stateVersion);
            statement.setObject(3, playerId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            results.next();
            return results.getLong(1);
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
