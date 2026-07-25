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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SecureTradeCoinIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;
    private SecureTradeRepository trades;

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
    void creationAndCoinOfferRetriesAreExact() throws Exception {
        UUID a = fundedPlayer("TradeA", 10_000);
        UUID b = fundedPlayer("TradeB", 10_000);
        UUID createOperation = UUID.randomUUID();

        SecureTradeSnapshot created = trades.createTrade(createOperation, a, b);
        assertEquals(created, trades.createTrade(createOperation, a, b));
        assertThrows(
                SecureTradeException.class,
                () -> trades.createTrade(createOperation, b, a)
        );

        UUID offerOperation = UUID.randomUUID();
        SecureTradeCoinOfferResult offer = trades.setCoinOffer(
                offerOperation,
                created.tradeId(),
                a,
                2_500,
                "trade.coin_offer"
        );
        SecureTradeCoinOfferResult retry = trades.setCoinOffer(
                offerOperation,
                created.tradeId(),
                a,
                2_500,
                "trade.coin_offer"
        );

        assertEquals(offer, retry);
        assertEquals(1L, offer.trade().revision());
        assertEquals(2_500L, offer.escrowAmountMinor());
        assertEquals(7_500L, offer.walletBalanceMinor());
        assertEquals(2_500L, trades.coinEscrow(created.tradeId(), a));
        assertEquals(7_500L, wallets.load(a).balanceMinor());
        assertEquals(1L, processedCount(offerOperation));
    }

    @Test
    void reducingCoinOfferRefundsOnlyTheDeltaAndAdvancesRevision() throws Exception {
        UUID a = fundedPlayer("RefundA", 10_000);
        UUID b = fundedPlayer("RefundB", 10_000);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);

        SecureTradeCoinOfferResult first = trades.setCoinOffer(
                UUID.randomUUID(), trade.tradeId(), a, 4_000, "trade.coin_offer"
        );
        SecureTradeCoinOfferResult reduced = trades.setCoinOffer(
                UUID.randomUUID(), trade.tradeId(), a, 1_500, "trade.coin_offer"
        );

        assertEquals(1L, first.trade().revision());
        assertEquals(2L, reduced.trade().revision());
        assertEquals(1_500L, reduced.escrowAmountMinor());
        assertEquals(8_500L, reduced.walletBalanceMinor());
        assertEquals(8_500L, wallets.load(a).balanceMinor());
    }

    @Test
    void anyOfferChangeInvalidatesPreviousConfirmation() throws Exception {
        UUID a = fundedPlayer("RevisionA", 10_000);
        UUID b = fundedPlayer("RevisionB", 10_000);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);
        SecureTradeCoinOfferResult firstOffer = trades.setCoinOffer(
                UUID.randomUUID(), trade.tradeId(), a, 1_000, "trade.coin_offer"
        );

        SecureTradeSnapshot confirmed = trades.confirm(UUID.randomUUID(), trade.tradeId(), a);
        assertEquals(firstOffer.trade().revision(), confirmed.playerAConfirmedRevision());
        assertNull(confirmed.playerBConfirmedRevision());

        SecureTradeCoinOfferResult changed = trades.setCoinOffer(
                UUID.randomUUID(), trade.tradeId(), b, 500, "trade.coin_offer"
        );
        assertEquals(2L, changed.trade().revision());
        assertNull(changed.trade().playerAConfirmedRevision());
        assertNull(changed.trade().playerBConfirmedRevision());
    }

    @Test
    void twoConfirmationsOnSameRevisionLockTradeAndPreventOfferMutation() throws Exception {
        UUID a = fundedPlayer("LockA", 10_000);
        UUID b = fundedPlayer("LockB", 10_000);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);
        SecureTradeCoinOfferResult offer = trades.setCoinOffer(
                UUID.randomUUID(), trade.tradeId(), a, 2_000, "trade.coin_offer"
        );

        SecureTradeSnapshot first = trades.confirm(UUID.randomUUID(), trade.tradeId(), a);
        assertEquals(SecureTradeStatus.OPEN, first.status());
        SecureTradeSnapshot locked = trades.confirm(UUID.randomUUID(), trade.tradeId(), b);

        assertEquals(SecureTradeStatus.LOCKED, locked.status());
        assertEquals(offer.trade().revision(), locked.playerAConfirmedRevision());
        assertEquals(offer.trade().revision(), locked.playerBConfirmedRevision());
        assertThrows(
                SecureTradeException.class,
                () -> trades.setCoinOffer(
                        UUID.randomUUID(), trade.tradeId(), a, 1_000, "trade.coin_offer"
                )
        );
    }

    @Test
    void concurrentConfirmationsProduceOneConsistentLockedRevision() throws Exception {
        UUID a = fundedPlayer("ConcurrentA", 10_000);
        UUID b = fundedPlayer("ConcurrentB", 10_000);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), a, 1_000, "trade.coin_offer");
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), b, 750, "trade.coin_offer");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SecureTradeSnapshot> first = executor.submit(
                    () -> trades.confirm(UUID.randomUUID(), trade.tradeId(), a)
            );
            Future<SecureTradeSnapshot> second = executor.submit(
                    () -> trades.confirm(UUID.randomUUID(), trade.tradeId(), b)
            );
            first.get();
            second.get();
        }

        SecureTradeSnapshot locked = trades.load(trade.tradeId());
        assertEquals(SecureTradeStatus.LOCKED, locked.status());
        assertEquals(Long.valueOf(2L), locked.playerAConfirmedRevision());
        assertEquals(Long.valueOf(2L), locked.playerBConfirmedRevision());
    }

    @Test
    void nonParticipantCannotEscrowOrConfirm() throws Exception {
        UUID a = fundedPlayer("OwnerA", 10_000);
        UUID b = fundedPlayer("OwnerB", 10_000);
        UUID outsider = fundedPlayer("Outsider", 10_000);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);

        assertThrows(
                SecureTradeException.class,
                () -> trades.setCoinOffer(
                        UUID.randomUUID(), trade.tradeId(), outsider, 100, "trade.coin_offer"
                )
        );
        assertThrows(
                SecureTradeException.class,
                () -> trades.confirm(UUID.randomUUID(), trade.tradeId(), outsider)
        );
        assertEquals(10_000L, wallets.load(outsider).balanceMinor());
    }

    private UUID fundedPlayer(String name, long amount) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, amount, "test.funding");
        return playerId;
    }

    private long processedCount(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM processed_operations
                     WHERE operation_id = ?
                     """)) {
            statement.setObject(1, operationId);
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
