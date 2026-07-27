package io.github.kevinrabbe.minecraftserver.common.analytics;

import io.github.kevinrabbe.minecraftserver.common.economy.BazaarMatchResult;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderRequest;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderSide;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BazaarMarketAnalyticsRepositoryIntegrationTest {
    private static final Instant WINDOW_START = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2040-01-01T00:00:00Z");
    private static final Instant SNAPSHOT_AT = Instant.parse("2035-01-01T00:00:00Z");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
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
        sessions = new PlayerSessionRepository(dataSource);
        wallets = new CoinWalletRepository(dataSource);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void currentBookReportsBestLevelsAndTotalOpenDepth() throws Exception {
        String commodity = uniqueCommodity("book");
        BazaarRepository bazaar = bazaarFor(commodity, 100);

        UUID bestBidder = player("MarketBidA");
        UUID lowerBidder = player("MarketBidB");
        UUID bestSeller = player("MarketAskA");
        UUID higherSeller = player("MarketAskB");
        wallets.creditFromSystem(UUID.randomUUID(), bestBidder, 1_000L, "verify.market.book.credit.a");
        wallets.creditFromSystem(UUID.randomUUID(), lowerBidder, 1_000L, "verify.market.book.credit.b");

        bazaar.createBuyOrder(
                UUID.randomUUID(),
                bestBidder,
                new BazaarOrderRequest(commodity, BazaarOrderSide.BUY, 3L, 100L),
                "verify.market.book.bid.best"
        );
        bazaar.createBuyOrder(
                UUID.randomUUID(),
                lowerBidder,
                new BazaarOrderRequest(commodity, BazaarOrderSide.BUY, 5L, 90L),
                "verify.market.book.bid.lower"
        );
        createSellOrder(bazaar, bestSeller, commodity, 2L, 120L, "verify.market.book.ask.best");
        createSellOrder(bazaar, higherSeller, commodity, 4L, 130L, "verify.market.book.ask.higher");

        BazaarMarketSummary summary = analytics().summarize(commodity, WINDOW_START, WINDOW_END);

        assertEquals(SNAPSHOT_AT, summary.snapshotAt());
        assertEquals(SNAPSHOT_AT, summary.historyObservedThrough());
        assertEquals(100L, summary.bestBidPriceMinor());
        assertEquals(120L, summary.bestAskPriceMinor());
        assertEquals(BigInteger.valueOf(3L), summary.bestBidQuantity());
        assertEquals(BigInteger.valueOf(2L), summary.bestAskQuantity());
        assertEquals(2L, summary.openBuyOrderCount());
        assertEquals(2L, summary.openSellOrderCount());
        assertEquals(BigInteger.valueOf(8L), summary.openBuyQuantity());
        assertEquals(BigInteger.valueOf(6L), summary.openSellQuantity());
        assertEquals(BigInteger.valueOf(20L), summary.quotedSpreadMinor());
        assertEquals(0L, summary.matchPassCount());
        assertEquals(0L, summary.fillCount());
        assertEquals(BigInteger.ZERO, summary.filledQuantity());
        assertEquals(BigInteger.ZERO, summary.grossTradeValueMinor());
        assertEquals(BigInteger.ZERO, summary.feesDestroyedMinor());
    }

    @Test
    void matcherHistoryUsesAppendOnlyMatchResultAndLeavesNoSyntheticBookState() throws Exception {
        String commodity = uniqueCommodity("fills");
        BazaarRepository bazaar = bazaarFor(commodity, 100);

        UUID buyer = player("MarketFillBuy");
        UUID seller = player("MarketFillSell");
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 1_000L, "verify.market.fill.credit");
        bazaar.createBuyOrder(
                UUID.randomUUID(),
                buyer,
                new BazaarOrderRequest(commodity, BazaarOrderSide.BUY, 2L, 100L),
                "verify.market.fill.buy"
        );
        createSellOrder(bazaar, seller, commodity, 2L, 100L, "verify.market.fill.sell");

        BazaarMatchResult match = bazaar.matchCommodity(
                UUID.randomUUID(),
                commodity,
                10,
                "verify.market.fill.match"
        );
        assertEquals(1, match.fills());
        assertEquals(2L, match.quantityFilled());
        assertEquals(200L, match.grossTradeValueMinor());
        assertEquals(2L, match.feesDestroyedMinor());

        BazaarMarketSummary summary = analytics().summarize(commodity, WINDOW_START, WINDOW_END);

        assertNull(summary.bestBidPriceMinor());
        assertNull(summary.bestAskPriceMinor());
        assertEquals(BigInteger.ZERO, summary.bestBidQuantity());
        assertEquals(BigInteger.ZERO, summary.bestAskQuantity());
        assertEquals(0L, summary.openBuyOrderCount());
        assertEquals(0L, summary.openSellOrderCount());
        assertEquals(BigInteger.ZERO, summary.openBuyQuantity());
        assertEquals(BigInteger.ZERO, summary.openSellQuantity());
        assertNull(summary.quotedSpreadMinor());
        assertEquals(1L, summary.matchPassCount());
        assertEquals(1L, summary.fillCount());
        assertEquals(BigInteger.valueOf(2L), summary.filledQuantity());
        assertEquals(BigInteger.valueOf(200L), summary.grossTradeValueMinor());
        assertEquals(BigInteger.valueOf(2L), summary.feesDestroyedMinor());
    }

    @Test
    void futureHistoryWindowIsEmptyWhileCurrentBookRemainsObservable() throws Exception {
        String commodity = uniqueCommodity("future");
        BazaarRepository bazaar = bazaarFor(commodity, 100);
        UUID buyer = player("MarketFuture");
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 500L, "verify.market.future.credit");
        bazaar.createBuyOrder(
                UUID.randomUUID(),
                buyer,
                new BazaarOrderRequest(commodity, BazaarOrderSide.BUY, 2L, 50L),
                "verify.market.future.buy"
        );

        Instant futureStart = Instant.parse("2041-01-01T00:00:00Z");
        Instant futureEnd = Instant.parse("2042-01-01T00:00:00Z");
        BazaarMarketSummary summary = analytics().summarize(commodity, futureStart, futureEnd);

        assertEquals(futureStart, summary.historyObservedThrough());
        assertEquals(50L, summary.bestBidPriceMinor());
        assertEquals(BigInteger.valueOf(2L), summary.openBuyQuantity());
        assertEquals(0L, summary.matchPassCount());
        assertEquals(0L, summary.fillCount());
        assertEquals(BigInteger.ZERO, summary.filledQuantity());
        assertEquals(BigInteger.ZERO, summary.grossTradeValueMinor());
        assertEquals(BigInteger.ZERO, summary.feesDestroyedMinor());
    }

    private BazaarMarketAnalyticsRepository analytics() {
        return new BazaarMarketAnalyticsRepository(
                dataSource,
                Clock.fixed(SNAPSHOT_AT, ZoneOffset.UTC)
        );
    }

    private BazaarRepository bazaarFor(String commodityDefinitionId, int feeBasisPoints) {
        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                commodityDefinitionId,
                "RAW_IRON",
                "Analytics Commodity",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
        return new BazaarRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, before, after) -> { },
                feeBasisPoints
        );
    }

    private UUID player(String name) throws Exception {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void createSellOrder(
            BazaarRepository bazaar,
            UUID seller,
            String commodity,
            long quantity,
            long priceMinor,
            String reason
    ) throws Exception {
        String backend = "analytics-bazaar-" + UUID.randomUUID().toString().substring(0, 8);
        SessionLease lease = sessions.openSession(seller, backend, null, Duration.ofMinutes(5));
        try {
            bazaar.createSellOrder(
                    UUID.randomUUID(),
                    lease.sessionId(),
                    backend,
                    lease.stateVersion(),
                    new BazaarOrderRequest(commodity, BazaarOrderSide.SELL, quantity, priceMinor),
                    null,
                    null,
                    new byte[] {1},
                    reason
            );
        } finally {
            sessions.disconnect(lease.sessionId(), backend);
        }
    }

    private static String uniqueCommodity(String lane) {
        return "material.analytics_" + lane + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
