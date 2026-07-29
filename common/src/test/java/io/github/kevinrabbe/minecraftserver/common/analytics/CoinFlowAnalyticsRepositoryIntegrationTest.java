package io.github.kevinrabbe.minecraftserver.common.analytics;

import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderRequest;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderSide;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.PveDeathLossRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CoinFlowAnalyticsRepositoryIntegrationTest {
    private static final Instant WINDOW_START = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2030-01-01T00:00:00Z");
    private static final String TEST_COMMODITY = "verify.coin_material";
    private static final String TEST_BACKEND = "analytics-coin-test";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private CoinWalletRepository wallets;
    private PveDeathLossRepository deathLoss;
    private BazaarRepository bazaar;

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
        deathLoss = new PveDeathLossRepository(dataSource);
        ItemCatalog items = new ItemCatalog(List.of(new ItemDefinition(
                TEST_COMMODITY,
                "IRON_INGOT",
                "Analytics Coin Material",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
        bazaar = new BazaarRepository(dataSource, items, (playerId, definitionId, quantity, before, after) -> { }, 100);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void classifiedSupplySeparatesFaucetSinkDeathLossTransferEscrowAndBazaarFee() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String faucetReason = "verify.analytics.reward." + suffix;
        String transferReason = "verify.analytics.transfer." + suffix;
        String sinkReason = "verify.analytics.sink." + suffix;
        String deathReason = "verify.analytics.death." + suffix;
        String escrowReason = "verify.analytics.escrow." + suffix;
        String sellReason = "verify.analytics.sell." + suffix;
        String matchReason = "verify.analytics.match." + suffix;

        UUID sourcePlayer = identities.ensurePlayer(UUID.randomUUID(), "CoinMetricA");
        UUID targetPlayer = identities.ensurePlayer(UUID.randomUUID(), "CoinMetricB");
        UUID sellerPlayer = identities.ensurePlayer(UUID.randomUUID(), "CoinMetricC");

        wallets.creditFromSystem(UUID.randomUUID(), sourcePlayer, 1_000L, faucetReason);
        wallets.transfer(UUID.randomUUID(), sourcePlayer, targetPlayer, 200L, transferReason);
        wallets.debitToSystem(UUID.randomUUID(), sourcePlayer, 150L, sinkReason);
        deathLoss.apply(
                UUID.randomUUID(),
                sourcePlayer,
                "verify.analytics.fixed_v1",
                lockedBalance -> 50L,
                deathReason
        );
        bazaar.createBuyOrder(
                UUID.randomUUID(),
                sourcePlayer,
                new BazaarOrderRequest(TEST_COMMODITY, BazaarOrderSide.BUY, 2L, 100L),
                escrowReason
        );

        SessionLease sellerSession = sessions.openSession(
                sellerPlayer,
                TEST_BACKEND,
                null,
                Duration.ofMinutes(5)
        );
        bazaar.createSellOrder(
                UUID.randomUUID(),
                sellerSession.sessionId(),
                TEST_BACKEND,
                sellerSession.stateVersion(),
                new BazaarOrderRequest(TEST_COMMODITY, BazaarOrderSide.SELL, 2L, 100L),
                null,
                null,
                new byte[]{1},
                sellReason
        );
        sessions.disconnect(sellerSession.sessionId(), TEST_BACKEND);

        bazaar.matchCommodity(UUID.randomUUID(), TEST_COMMODITY, 1, matchReason);

        CoinFlowAnalyticsRepository analytics = new CoinFlowAnalyticsRepository(
                dataSource,
                Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
        CoinFlowSummary summary = analytics.summarize(WINDOW_START, WINDOW_END, 500);

        assertEquals(Instant.parse("2029-01-01T00:00:00Z"), summary.observedThrough());
        assertFalse(summary.reasonsTruncated());

        CoinFlowReasonSummary faucet = reason(summary, faucetReason);
        assertEquals(BigInteger.valueOf(1_000L), faucet.createdMinor());
        assertEquals(BigInteger.ZERO, faucet.destroyedMinor());
        assertEquals(BigInteger.valueOf(1_000L), faucet.netSupplyChangeMinor());
        assertEquals(BigInteger.valueOf(1_000L), faucet.grossMovementMinor());
        assertEquals(1L, faucet.operationCount());

        CoinFlowReasonSummary transfer = reason(summary, transferReason);
        assertEquals(BigInteger.ZERO, transfer.createdMinor());
        assertEquals(BigInteger.ZERO, transfer.destroyedMinor());
        assertEquals(BigInteger.ZERO, transfer.netSupplyChangeMinor());
        assertEquals(BigInteger.valueOf(400L), transfer.grossMovementMinor());
        assertEquals(1L, transfer.operationCount());

        CoinFlowReasonSummary sink = reason(summary, sinkReason);
        assertEquals(BigInteger.ZERO, sink.createdMinor());
        assertEquals(BigInteger.valueOf(150L), sink.destroyedMinor());
        assertEquals(BigInteger.valueOf(-150L), sink.netSupplyChangeMinor());
        assertEquals(BigInteger.valueOf(150L), sink.grossMovementMinor());
        assertEquals(1L, sink.operationCount());

        CoinFlowReasonSummary death = reason(summary, deathReason);
        assertEquals(BigInteger.ZERO, death.createdMinor());
        assertEquals(BigInteger.valueOf(50L), death.destroyedMinor());
        assertEquals(BigInteger.valueOf(-50L), death.netSupplyChangeMinor());
        assertEquals(BigInteger.valueOf(50L), death.grossMovementMinor());
        assertEquals(1L, death.operationCount());

        CoinFlowReasonSummary escrow = reason(summary, escrowReason);
        assertEquals(BigInteger.ZERO, escrow.createdMinor());
        assertEquals(BigInteger.ZERO, escrow.destroyedMinor());
        assertEquals(BigInteger.ZERO, escrow.netSupplyChangeMinor());
        assertEquals(BigInteger.valueOf(200L), escrow.grossMovementMinor());
        assertEquals(1L, escrow.operationCount());

        CoinFlowReasonSummary match = reason(summary, matchReason);
        assertEquals(BigInteger.ZERO, match.createdMinor());
        assertEquals(BigInteger.valueOf(2L), match.destroyedMinor());
        assertEquals(BigInteger.valueOf(-2L), match.netSupplyChangeMinor());
        assertEquals(BigInteger.valueOf(198L), match.grossMovementMinor());
        assertEquals(1L, match.operationCount());

        assertTrue(summary.confirmedCreatedMinor().compareTo(BigInteger.valueOf(1_000L)) >= 0);
        assertTrue(summary.confirmedDestroyedMinor().compareTo(BigInteger.valueOf(202L)) >= 0);
    }

    @Test
    void futureWindowHasNoObservedCoinFlow() throws Exception {
        CoinFlowAnalyticsRepository analytics = new CoinFlowAnalyticsRepository(
                dataSource,
                Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
        Instant futureStart = Instant.parse("2031-01-01T00:00:00Z");
        Instant futureEnd = Instant.parse("2032-01-01T00:00:00Z");

        CoinFlowSummary summary = analytics.summarize(futureStart, futureEnd, 10);

        assertEquals(futureStart, summary.observedThrough());
        assertEquals(BigInteger.ZERO, summary.confirmedCreatedMinor());
        assertEquals(BigInteger.ZERO, summary.confirmedDestroyedMinor());
        assertEquals(BigInteger.ZERO, summary.confirmedNetSupplyChangeMinor());
        assertEquals(BigInteger.ZERO, summary.grossMovementMinor());
        assertEquals(0L, summary.classifiedOperationCount());
        assertEquals(0L, summary.unclassifiedOperationCount());
        assertEquals(BigInteger.ZERO, summary.unclassifiedGrossMovementMinor());
        assertEquals(0L, summary.operationCount());
        assertEquals(0, summary.reasons().size());
        assertFalse(summary.reasonsTruncated());
        assertTrue(summary.supplyClassificationComplete());
    }

    private static CoinFlowReasonSummary reason(CoinFlowSummary summary, String reason) {
        return summary.reasons().stream()
                .filter(row -> row.reason().equals(reason))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Coin analytics reason: " + reason));
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
