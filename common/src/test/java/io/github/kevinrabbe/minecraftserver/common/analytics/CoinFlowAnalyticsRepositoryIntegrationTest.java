package io.github.kevinrabbe.minecraftserver.common.analytics;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CoinFlowAnalyticsRepositoryIntegrationTest {
    private static final Instant WINDOW_START = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2030-01-01T00:00:00Z");

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

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void operationNetSeparatesTrueSupplyChangeFromInternalMovement() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String faucetReason = "verify.analytics.reward." + suffix;
        String transferReason = "verify.analytics.transfer." + suffix;
        String sinkReason = "verify.analytics.sink." + suffix;

        UUID sourcePlayer = identities.ensurePlayer(UUID.randomUUID(), "CoinMetricA");
        UUID targetPlayer = identities.ensurePlayer(UUID.randomUUID(), "CoinMetricB");

        wallets.creditFromSystem(UUID.randomUUID(), sourcePlayer, 1_000L, faucetReason);
        wallets.transfer(UUID.randomUUID(), sourcePlayer, targetPlayer, 200L, transferReason);
        wallets.debitToSystem(UUID.randomUUID(), sourcePlayer, 150L, sinkReason);

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
        assertEquals(BigInteger.ZERO, summary.createdMinor());
        assertEquals(BigInteger.ZERO, summary.destroyedMinor());
        assertEquals(BigInteger.ZERO, summary.netSupplyChangeMinor());
        assertEquals(BigInteger.ZERO, summary.grossMovementMinor());
        assertEquals(0L, summary.operationCount());
        assertEquals(0, summary.reasons().size());
        assertFalse(summary.reasonsTruncated());
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
