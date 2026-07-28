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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SecureTradeConfirmationRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;
    private SecureTradeRepository trades;
    private SecureTradeConfirmationRepository confirmations;

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
        trades = new SecureTradeRepository(dataSource);
        confirmations = new SecureTradeConfirmationRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        secure_trade_deliveries,
                        secure_trade_unique_items,
                        secure_trade_commodity_escrow,
                        secure_trade_coin_escrow,
                        secure_trades,
                        economic_ledger,
                        processed_operations,
                        wallets,
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
    void staleViewedRevisionFailsWithoutConfirmingNewOffer() throws Exception {
        UUID a = fundedPlayer("ConfirmA", 10_000);
        UUID b = fundedPlayer("ConfirmB", 10_000);
        SecureTradeSnapshot created = trades.createTrade(UUID.randomUUID(), a, b);
        SecureTradeSnapshot revisionOne = trades.setCoinOffer(
                UUID.randomUUID(), created.tradeId(), a, 1_000, "trade.coin_offer"
        ).trade();
        SecureTradeSnapshot revisionTwo = trades.setCoinOffer(
                UUID.randomUUID(), created.tradeId(), b, 500, "trade.coin_offer"
        ).trade();

        assertThrows(
                SecureTradeException.class,
                () -> confirmations.confirmViewedRevision(created.tradeId(), a, revisionOne.revision())
        );

        SecureTradeSnapshot unchanged = trades.load(created.tradeId());
        assertEquals(revisionTwo.revision(), unchanged.revision());
        assertNull(unchanged.playerAConfirmedRevision());
        assertNull(unchanged.playerBConfirmedRevision());
    }

    @Test
    void twoExactRevisionConfirmationsLockAndLockedRetryIsIdempotent() throws Exception {
        UUID a = fundedPlayer("LockViewA", 10_000);
        UUID b = fundedPlayer("LockViewB", 10_000);
        SecureTradeSnapshot created = trades.createTrade(UUID.randomUUID(), a, b);
        SecureTradeSnapshot viewed = trades.setCoinOffer(
                UUID.randomUUID(), created.tradeId(), a, 2_000, "trade.coin_offer"
        ).trade();

        SecureTradeSnapshot first = confirmations.confirmViewedRevision(
                viewed.tradeId(), a, viewed.revision()
        );
        assertEquals(SecureTradeStatus.OPEN, first.status());

        SecureTradeSnapshot locked = confirmations.confirmViewedRevision(
                viewed.tradeId(), b, viewed.revision()
        );
        assertEquals(SecureTradeStatus.LOCKED, locked.status());
        assertEquals(Long.valueOf(viewed.revision()), locked.playerAConfirmedRevision());
        assertEquals(Long.valueOf(viewed.revision()), locked.playerBConfirmedRevision());

        assertEquals(
                locked,
                confirmations.confirmViewedRevision(viewed.tradeId(), a, viewed.revision())
        );
    }

    @Test
    void concurrentExactRevisionConfirmationsProduceOneLockedRevision() throws Exception {
        UUID a = fundedPlayer("ConcurrentA", 10_000);
        UUID b = fundedPlayer("ConcurrentB", 10_000);
        SecureTradeSnapshot created = trades.createTrade(UUID.randomUUID(), a, b);
        SecureTradeSnapshot viewed = trades.setCoinOffer(
                UUID.randomUUID(), created.tradeId(), a, 1_000, "trade.coin_offer"
        ).trade();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SecureTradeSnapshot> first = executor.submit(
                    () -> confirmations.confirmViewedRevision(viewed.tradeId(), a, viewed.revision())
            );
            Future<SecureTradeSnapshot> second = executor.submit(
                    () -> confirmations.confirmViewedRevision(viewed.tradeId(), b, viewed.revision())
            );
            first.get();
            second.get();
        }

        SecureTradeSnapshot locked = trades.load(viewed.tradeId());
        assertEquals(SecureTradeStatus.LOCKED, locked.status());
        assertEquals(Long.valueOf(viewed.revision()), locked.playerAConfirmedRevision());
        assertEquals(Long.valueOf(viewed.revision()), locked.playerBConfirmedRevision());
    }

    private UUID fundedPlayer(String name, long amount) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, amount, "test.funding");
        return playerId;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
