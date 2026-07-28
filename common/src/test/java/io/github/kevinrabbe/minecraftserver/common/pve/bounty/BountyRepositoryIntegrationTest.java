package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BountyRepositoryIntegrationTest {
    private static final BountyFamilyId SPIDER = new BountyFamilyId("spider");
    private static final BountyTierDefinition TIER = new BountyTierDefinition(
            SPIDER,
            1,
            1_000,
            2,
            "spider_queen",
            List.of("spider_silk", "venom")
    );
    private static final Instant START = Instant.parse("2026-08-14T18:00:00Z");

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
                6
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
                        bounty_summons,
                        bounty_pouch_balances,
                        bounty_pouches,
                        bounty_contracts,
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
    void prepareRetryRemainsOriginalReadySnapshotAfterRuntimeActivation() throws Exception {
        MutableClock clock = new MutableClock(START);
        BountyRepository bounties = repository(clock, (contractId, tier) -> Map.of("spider_silk", 3L));
        UUID playerId = fundedPlayer("PrepareRetry");
        BountyContractSnapshot ready = startAndReady(bounties, playerId);
        UUID prepareOperation = UUID.randomUUID();

        BountySummonPrepareResult prepared = bounties.prepareSummon(
                prepareOperation,
                ready.contractId(),
                playerId,
                "bounty.prepare"
        );
        assertEquals(BountySummonStatus.READY, prepared.summon().status());
        assertEquals(BountyContractStatus.SUMMONED, prepared.contract().status());

        BountySummonLeaseResult active = bounties.claimSummon(
                UUID.randomUUID(),
                prepared.summon().summonId(),
                "paper-a",
                "bounty.claim"
        );
        assertEquals(BountySummonStatus.ACTIVE, active.summon().status());

        BountySummonPrepareResult retry = bounties.prepareSummon(
                prepareOperation,
                ready.contractId(),
                playerId,
                "bounty.prepare"
        );

        assertEquals(prepared, retry);
        assertEquals(BountySummonStatus.READY, retry.summon().status());
        assertEquals(BountySummonStatus.ACTIVE, bounties.loadSummon(prepared.summon().summonId()).status());
    }

    @Test
    void heartbeatWritesOneAppendOnlyIdempotencyRecordAndExactRetry() throws Exception {
        MutableClock clock = new MutableClock(START);
        BountyRepository bounties = repository(clock, (contractId, tier) -> Map.of("spider_silk", 2L));
        UUID playerId = fundedPlayer("HeartbeatOwner");
        BountySummonLeaseResult active = activeSummon(bounties, playerId, "paper-a");
        UUID operationId = UUID.randomUUID();

        clock.advance(Duration.ofSeconds(5));
        BountySummonLeaseResult first = bounties.heartbeatSummon(
                operationId,
                active.summon().summonId(),
                "paper-a",
                active.summon().stateVersion(),
                "bounty.heartbeat"
        );
        BountySummonLeaseResult retry = bounties.heartbeatSummon(
                operationId,
                active.summon().summonId(),
                "paper-a",
                active.summon().stateVersion(),
                "bounty.heartbeat"
        );

        assertEquals(first, retry);
        assertEquals(active.summon().stateVersion() + 1, first.summon().stateVersion());
        assertEquals(1L, processedOperationCount(operationId));
        assertEquals("BOUNTY_SUMMON_HEARTBEAT", processedOperationType(operationId));
        assertThrows(
                BountyException.class,
                () -> bounties.heartbeatSummon(
                        operationId,
                        active.summon().summonId(),
                        "paper-a",
                        first.summon().stateVersion(),
                        "bounty.heartbeat"
                )
        );
    }

    @Test
    void expiredLeaseCanBeReclaimedWithoutCreatingSecondSummon() throws Exception {
        MutableClock clock = new MutableClock(START);
        BountyRepository bounties = repository(clock, (contractId, tier) -> Map.of("spider_silk", 1L));
        UUID playerId = fundedPlayer("LeaseRecovery");
        BountySummonLeaseResult first = activeSummon(bounties, playerId, "paper-a");
        Instant originalActivation = first.summon().activatedAt();

        clock.advance(Duration.ofSeconds(31));
        BountySummonLeaseResult reclaimed = bounties.claimSummon(
                UUID.randomUUID(),
                first.summon().summonId(),
                "paper-b",
                "bounty.reclaim"
        );

        assertEquals(first.summon().summonId(), reclaimed.summon().summonId());
        assertEquals("paper-b", reclaimed.summon().ownerBackendId());
        assertEquals(first.summon().stateVersion() + 1, reclaimed.summon().stateVersion());
        assertEquals(originalActivation, reclaimed.summon().activatedAt());
        assertEquals(1L, tableCount("bounty_summons"));
    }

    @Test
    void staleBackendCannotHeartbeatOrCompleteAfterReclaim() throws Exception {
        MutableClock clock = new MutableClock(START);
        BountyRepository bounties = repository(clock, (contractId, tier) -> Map.of("spider_silk", 4L));
        UUID playerId = fundedPlayer("StaleBackend");
        BountySummonLeaseResult first = activeSummon(bounties, playerId, "paper-a");

        clock.advance(Duration.ofSeconds(31));
        BountySummonLeaseResult reclaimed = bounties.claimSummon(
                UUID.randomUUID(),
                first.summon().summonId(),
                "paper-b",
                "bounty.reclaim"
        );

        assertThrows(
                BountyException.class,
                () -> bounties.heartbeatSummon(
                        UUID.randomUUID(),
                        first.summon().summonId(),
                        "paper-a",
                        first.summon().stateVersion(),
                        "bounty.heartbeat"
                )
        );
        assertThrows(
                BountyException.class,
                () -> bounties.completeBoss(
                        UUID.randomUUID(),
                        first.summon().summonId(),
                        "paper-a",
                        first.summon().stateVersion(),
                        "bounty.complete"
                )
        );
        assertEquals("paper-b", reclaimed.summon().ownerBackendId());
    }

    @Test
    void completionCreditsAllowedPouchRewardsExactlyOnce() throws Exception {
        MutableClock clock = new MutableClock(START);
        BountyRepository bounties = repository(
                clock,
                (contractId, tier) -> Map.of("spider_silk", 5L, "venom", 2L)
        );
        UUID playerId = fundedPlayer("CompletionOwner");
        BountySummonLeaseResult active = activeSummon(bounties, playerId, "paper-a");
        UUID operationId = UUID.randomUUID();

        BountyCompletionResult first = bounties.completeBoss(
                operationId,
                active.summon().summonId(),
                "paper-a",
                active.summon().stateVersion(),
                "bounty.complete"
        );
        BountyCompletionResult retry = bounties.completeBoss(
                operationId,
                active.summon().summonId(),
                "paper-a",
                active.summon().stateVersion(),
                "bounty.complete"
        );

        assertEquals(first, retry);
        assertEquals(BountyContractStatus.COMPLETED, first.contract().status());
        assertEquals(5L, pouchQuantity(playerId, "spider_silk"));
        assertEquals(2L, pouchQuantity(playerId, "venom"));
        assertEquals(BountySummonStatus.DEFEATED, bounties.loadSummon(active.summon().summonId()).status());
        assertEquals(1L, processedOperationCount(operationId));
    }

    @Test
    void invalidRewardRollsBackBossAndContractSettlement() throws Exception {
        MutableClock clock = new MutableClock(START);
        BountyRepository bounties = repository(clock, (contractId, tier) -> Map.of("not_allowed", 99L));
        UUID playerId = fundedPlayer("InvalidReward");
        BountySummonLeaseResult active = activeSummon(bounties, playerId, "paper-a");

        assertThrows(
                BountyException.class,
                () -> bounties.completeBoss(
                        UUID.randomUUID(),
                        active.summon().summonId(),
                        "paper-a",
                        active.summon().stateVersion(),
                        "bounty.complete"
                )
        );

        assertEquals(BountySummonStatus.ACTIVE, bounties.loadSummon(active.summon().summonId()).status());
        assertEquals(BountyContractStatus.SUMMONED, bounties.loadContract(active.summon().contractId()).status());
        assertEquals(0L, tableCount("bounty_pouch_balances"));
    }

    @Test
    void explicitFailureIsTerminalAndIdempotent() throws Exception {
        MutableClock clock = new MutableClock(START);
        BountyRepository bounties = repository(clock, (contractId, tier) -> Map.of("spider_silk", 1L));
        UUID playerId = fundedPlayer("FailureOwner");
        BountySummonLeaseResult active = activeSummon(bounties, playerId, "paper-a");
        UUID operationId = UUID.randomUUID();

        BountyContractSnapshot first = bounties.failBoss(
                operationId,
                active.summon().summonId(),
                "paper-a",
                active.summon().stateVersion(),
                "bounty.fail"
        );
        BountyContractSnapshot retry = bounties.failBoss(
                operationId,
                active.summon().summonId(),
                "paper-a",
                active.summon().stateVersion(),
                "bounty.fail"
        );

        assertEquals(first, retry);
        assertEquals(BountyContractStatus.FAILED, first.status());
        assertEquals(BountySummonStatus.FAILED, bounties.loadSummon(active.summon().summonId()).status());
        assertEquals(0L, tableCount("bounty_pouch_balances"));
    }

    @Test
    void startAndKillProgressAreBoundToOriginalRequests() throws Exception {
        MutableClock clock = new MutableClock(START);
        BountyRepository bounties = repository(clock, (contractId, tier) -> Map.of("spider_silk", 1L));
        UUID playerId = fundedPlayer("OperationBinding");
        UUID startOperation = UUID.randomUUID();

        BountyContractStartResult start = bounties.startContract(
                startOperation, playerId, SPIDER, 1, "bounty.start"
        );
        assertEquals(9_000L, start.walletBalanceMinor());
        assertEquals(start, bounties.startContract(startOperation, playerId, SPIDER, 1, "bounty.start"));
        assertThrows(
                BountyException.class,
                () -> bounties.startContract(startOperation, playerId, SPIDER, 1, "bounty.other")
        );

        UUID progressOperation = UUID.randomUUID();
        BountyContractSnapshot progress = bounties.recordEligibleKills(
                progressOperation, start.contract().contractId(), playerId, 50, "bounty.progress"
        );
        assertEquals(2, progress.eligibleKillProgress());
        assertEquals(BountyContractStatus.SUMMON_READY, progress.status());
        assertEquals(1, progress.summonAuthorizationsRemaining());
        assertEquals(
                progress,
                bounties.recordEligibleKills(
                        progressOperation, start.contract().contractId(), playerId, 50, "bounty.progress"
                )
        );
        assertThrows(
                BountyException.class,
                () -> bounties.recordEligibleKills(
                        progressOperation, start.contract().contractId(), playerId, 1, "bounty.progress"
                )
        );
    }

    private BountyRepository repository(MutableClock clock, BountyRewardResolver resolver) {
        return new BountyRepository(
                dataSource,
                new BountyTierCatalog(List.of(TIER)),
                resolver,
                Duration.ofSeconds(30),
                clock
        );
    }

    private UUID fundedPlayer(String name) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 10_000, "test.funding");
        return playerId;
    }

    private BountyContractSnapshot startAndReady(BountyRepository bounties, UUID playerId) throws SQLException {
        BountyContractStartResult start = bounties.startContract(
                UUID.randomUUID(), playerId, SPIDER, 1, "bounty.start"
        );
        return bounties.recordEligibleKills(
                UUID.randomUUID(), start.contract().contractId(), playerId, 2, "bounty.progress"
        );
    }

    private BountySummonLeaseResult activeSummon(
            BountyRepository bounties,
            UUID playerId,
            String backendId
    ) throws SQLException {
        BountyContractSnapshot ready = startAndReady(bounties, playerId);
        BountySummonPrepareResult prepared = bounties.prepareSummon(
                UUID.randomUUID(), ready.contractId(), playerId, "bounty.prepare"
        );
        return bounties.claimSummon(
                UUID.randomUUID(), prepared.summon().summonId(), backendId, "bounty.claim"
        );
    }

    private long pouchQuantity(UUID playerId, String definitionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT quantity
                     FROM bounty_pouch_balances
                     WHERE player_id = ? AND family_id = 'spider' AND commodity_definition_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, definitionId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getLong("quantity");
            }
        }
    }

    private long processedOperationCount(UUID operationId) throws SQLException {
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

    private String processedOperationType(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT operation_type
                     FROM processed_operations
                     WHERE operation_id = ?
                     """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getString("operation_type");
            }
        }
    }

    private long tableCount(String table) throws SQLException {
        if (!List.of("bounty_summons", "bounty_pouch_balances").contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock supports UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
