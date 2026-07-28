package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyBossMaterializationRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContractSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContractStartResult;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyFamilyId;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyKillProgressRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountySummonLeaseResult;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountySummonPrepareResult;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyTierCatalog;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyTierDefinition;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntityKillClaim;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntitySpawnRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntitySpawnSnapshot;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceDefinition;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceSnapshot;
import org.junit.jupiter.api.AfterAll;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BountyLifecycleIntegrityVerifierIntegrationTest {
    private static final Instant START = Instant.parse("2026-08-15T18:00:00Z");
    private static final BountyFamilyId FAMILY = new BountyFamilyId("spider");
    private static final String BOSS = "boss.spider.t1";
    private static final String REWARD = "material.spider_silk";
    private static final String RESOURCE_DROP = "verify.bounty_drop";
    private static final String RESOURCE_DEFINITION = "verify.bounty_spider";
    private static final String ZONE = "verify_bounty_pve";
    private static final String TEMPLATE = "bounty-pve-v1";
    private static final SkillId COMBAT = new SkillId("combat");
    private static final BountyTierDefinition TIER = new BountyTierDefinition(
            FAMILY, 1, 100, 2, BOSS, List.of(REWARD)
    );

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private CoinWalletRepository wallets;
    private BountyTierCatalog bountyTiers;
    private ResourceSourceRepository resourceSources;
    private ResourceEntitySpawnRepository entitySpawns;
    private BountyLifecycleIntegrityVerifier verifier;

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
        sessions = new PlayerSessionRepository(dataSource);
        wallets = new CoinWalletRepository(dataSource);
        bountyTiers = new BountyTierCatalog(List.of(TIER));

        ItemCatalog items = new ItemCatalog(List.of(new ItemDefinition(
                RESOURCE_DROP,
                "STRING",
                "Verifier Spider Drop",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(curve(COMBAT)));
        ResourceSourceCatalog sourceCatalog = new ResourceSourceCatalog(
                List.of(new ResourceSourceDefinition(
                        RESOURCE_DEFINITION,
                        ZONE,
                        TEMPLATE,
                        RESOURCE_DROP,
                        1,
                        COMBAT,
                        10,
                        Duration.ofMillis(1)
                )),
                items,
                skills
        );
        resourceSources = new ResourceSourceRepository(dataSource, sourceCatalog);
        entitySpawns = new ResourceEntitySpawnRepository(dataSource, sourceCatalog);
        verifier = new BountyLifecycleIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        bounty_boss_materializations,
                        bounty_managed_kill_progress,
                        bounty_summons,
                        bounty_pouch_balances,
                        bounty_pouches,
                        bounty_contracts,
                        resource_entity_kill_claims,
                        resource_entity_spawns,
                        resource_entity_sources,
                        resource_harvest_fulfillments,
                        resource_harvests,
                        resource_sources,
                        skill_xp_awards,
                        pending_commodity_deliveries,
                        economic_ledger,
                        processed_operations,
                        player_skills,
                        player_sessions,
                        zone_instances,
                        backends,
                        player_names,
                        player_state,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
            statement.execute("INSERT INTO backends(backend_id, status) VALUES ('paper-a', 'ONLINE'), ('paper-b', 'ONLINE')");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void healthyCompletedAndFailedLifecyclesReconcileCleanly() throws Exception {
        MutableClock clock = new MutableClock(START);
        BountyRepository bounties = repository(clock);
        BountyBossMaterializationRepository materializations = new BountyBossMaterializationRepository(dataSource, bountyTiers);

        UUID completedPlayer = fundedPlayer("BountyDone");
        ActiveContext completed = active(bounties, completedPlayer);
        BountySummonLeaseResult heartbeat = bounties.heartbeatSummon(
                UUID.randomUUID(), completed.summon().summonId(), "paper-a",
                completed.summon().stateVersion(), "bounty.heartbeat"
        );
        materializations.record(
                UUID.randomUUID(), heartbeat.summon().summonId(), "paper-a", BOSS,
                UUID.randomUUID(), "world", 20.5, 64.0, -4.5
        );
        bounties.completeBoss(
                UUID.randomUUID(), heartbeat.summon().summonId(), "paper-a",
                heartbeat.summon().stateVersion(), "bounty.complete"
        );

        UUID failedPlayer = fundedPlayer("BountyFail");
        ActiveContext failed = active(bounties, failedPlayer);
        bounties.failBoss(
                UUID.randomUUID(), failed.summon().summonId(), "paper-a",
                failed.summon().stateVersion(), "bounty.fail"
        );

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void feeLedgerDriftIsReported() throws Exception {
        BountyRepository bounties = repository(new MutableClock(START));
        UUID player = fundedPlayer("BountyFee");
        BountyContractStartResult start = bounties.startContract(
                UUID.randomUUID(), player, FAMILY, 1, "bounty.start"
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE economic_ledger
                    SET amount = amount + 1
                    WHERE operation_id = ?
                    """)) {
                statement.setObject(1, start.contract().contractId() == null ? null : feeOperation(start.contract().contractId()));
                if (statement.executeUpdate() == 0) {
                    try (PreparedStatement fallback = connection.prepareStatement("""
                            UPDATE economic_ledger l
                            SET amount = amount + 1
                            FROM bounty_contracts c
                            WHERE c.contract_id = ? AND l.operation_id = c.fee_operation_id
                            """)) {
                        fallback.setObject(1, start.contract().contractId());
                        assertEquals(1, fallback.executeUpdate());
                    }
                }
            }
        });

        assertIssue("BOUNTY_CONTRACT_START_EVIDENCE_MISMATCH", start.contract().contractId().toString());
    }

    @Test
    void prepareSnapshotDriftIsReported() throws Exception {
        BountyRepository bounties = repository(new MutableClock(START));
        UUID player = fundedPlayer("BountyPrep");
        BountyContractSnapshot ready = ready(bounties, player);
        BountySummonPrepareResult prepared = bounties.prepareSummon(
                UUID.randomUUID(), ready.contractId(), player, "bounty.prepare"
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE processed_operations
                    SET result = jsonb_set(result, '{summon,status}', '"ACTIVE"'::jsonb)
                    WHERE operation_type = 'BOUNTY_SUMMON_PREPARE'
                      AND result #>> '{summon,summon_id}' = ?
                    """)) {
                statement.setString(1, prepared.summon().summonId().toString());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("BOUNTY_SUMMON_PREPARE_EVIDENCE_MISMATCH", prepared.summon().summonId().toString());
    }

    @Test
    void heartbeatHistoricalStateDriftIsReported() throws Exception {
        BountyRepository bounties = repository(new MutableClock(START));
        UUID player = fundedPlayer("BountyBeat");
        ActiveContext active = active(bounties, player);
        UUID heartbeatOperation = UUID.randomUUID();
        bounties.heartbeatSummon(
                heartbeatOperation, active.summon().summonId(), "paper-a",
                active.summon().stateVersion(), "bounty.heartbeat"
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE processed_operations
                    SET result = jsonb_set(result, '{summon,owner_backend_id}', '"paper-b"'::jsonb)
                    WHERE operation_id = ?
                    """)) {
                statement.setObject(1, heartbeatOperation);
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("BOUNTY_SUMMON_LEASE_EVIDENCE_MISMATCH", heartbeatOperation.toString());
    }

    @Test
    void materializationEvidenceDriftIsReported() throws Exception {
        BountyRepository bounties = repository(new MutableClock(START));
        UUID player = fundedPlayer("BountyMat");
        ActiveContext active = active(bounties, player);
        BountyBossMaterializationRepository materializations = new BountyBossMaterializationRepository(dataSource, bountyTiers);
        UUID materializeOperation = UUID.randomUUID();
        materializations.record(
                materializeOperation, active.summon().summonId(), "paper-a", BOSS,
                UUID.randomUUID(), "world", 10.0, 64.0, 10.0
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE processed_operations
                    SET result = jsonb_set(result, '{materialization,world_name}', '"other"'::jsonb)
                    WHERE operation_id = ?
                    """)) {
                statement.setObject(1, materializeOperation);
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("BOUNTY_BOSS_MATERIALIZATION_EVIDENCE_MISMATCH", active.summon().summonId().toString());
    }

    @Test
    void failedTerminalEvidenceDriftIsReported() throws Exception {
        BountyRepository bounties = repository(new MutableClock(START));
        UUID player = fundedPlayer("BountyTerm");
        ActiveContext active = active(bounties, player);
        UUID failOperation = UUID.randomUUID();
        BountyContractSnapshot failed = bounties.failBoss(
                failOperation, active.summon().summonId(), "paper-a",
                active.summon().stateVersion(), "bounty.fail"
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE processed_operations
                    SET result = jsonb_set(result, '{contract,status}', '"COMPLETED"'::jsonb)
                    WHERE operation_id = ?
                    """)) {
                statement.setObject(1, failOperation);
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("BOUNTY_TERMINAL_EVIDENCE_MISMATCH", failed.contractId().toString());
    }

    @Test
    void managedKillBridgeDriftIsReported() throws Exception {
        BountyRepository bounties = repository(new MutableClock(START));
        UUID player = fundedPlayer("BountyKill");
        bounties.startContract(UUID.randomUUID(), player, FAMILY, 1, "bounty.start");
        UUID instance = createInstance();
        SessionLease session = sessions.openSession(player, "paper-a", instance, Duration.ofSeconds(30));
        ResourceSourceSnapshot source = resourceSources.ensureSource(instance, "spider.01", RESOURCE_DEFINITION);
        entitySpawns.ensureEntitySource(source.sourceId());
        ResourceEntitySpawnSnapshot pending = entitySpawns.reserveSpawn(source.sourceId(), Duration.ofSeconds(5)).orElseThrow();
        UUID entityUuid = UUID.randomUUID();
        entitySpawns.confirmSpawn(pending.spawnId(), entityUuid, Duration.ofMinutes(1));
        ResourceEntityKillClaim claim = entitySpawns.prepareKillClaim(pending.spawnId(), entityUuid);
        resourceSources.harvest(
                claim.operationId(), session.sessionId(), "paper-a", session.stateVersion(),
                source.sourceId(), "resource.entity_kill"
        );
        new BountyKillProgressRepository(dataSource).recordManagedKill(
                claim.operationId(), player, RESOURCE_DEFINITION, FAMILY, 1, "bounty.managed_kill"
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE bounty_managed_kill_progress
                    SET source_definition_id = 'verify.wrong_source'
                    WHERE resource_kill_operation_id = ?
                    """)) {
                statement.setObject(1, claim.operationId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals("BOUNTY_MANAGED_KILL_EVIDENCE_MISMATCH")));
    }

    private BountyRepository repository(MutableClock clock) {
        return new BountyRepository(
                dataSource,
                bountyTiers,
                (contractId, tier) -> Map.of(REWARD, 2L),
                Duration.ofSeconds(30),
                clock
        );
    }

    private UUID fundedPlayer(String name) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 10_000, "test.funding");
        return playerId;
    }

    private BountyContractSnapshot ready(BountyRepository bounties, UUID playerId) throws SQLException {
        BountyContractStartResult started = bounties.startContract(
                UUID.randomUUID(), playerId, FAMILY, 1, "bounty.start"
        );
        return bounties.recordEligibleKills(
                UUID.randomUUID(), started.contract().contractId(), playerId, 2, "bounty.progress"
        );
    }

    private ActiveContext active(BountyRepository bounties, UUID playerId) throws SQLException {
        BountyContractSnapshot ready = ready(bounties, playerId);
        BountySummonPrepareResult prepared = bounties.prepareSummon(
                UUID.randomUUID(), ready.contractId(), playerId, "bounty.prepare"
        );
        BountySummonLeaseResult active = bounties.claimSummon(
                UUID.randomUUID(), prepared.summon().summonId(), "paper-a", "bounty.claim"
        );
        return new ActiveContext(ready, active);
    }

    private UUID createInstance() throws SQLException {
        UUID instanceId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO zone_instances(
                         instance_id, zone_id, template_version, backend_id, status,
                         player_count, soft_capacity, hard_capacity
                     ) VALUES (?, ?, ?, 'paper-a', 'ACTIVE', 0, 20, 30)
                     """)) {
            statement.setObject(1, instanceId);
            statement.setString(2, ZONE);
            statement.setString(3, TEMPLATE);
            statement.executeUpdate();
        }
        return instanceId;
    }

    private UUID feeOperation(UUID contractId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT fee_operation_id FROM bounty_contracts WHERE contract_id = ?")) {
            statement.setObject(1, contractId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("missing bounty contract");
                return row.getObject(1, UUID.class);
            }
        }
    }

    private void assertIssue(String code, String subjectId) throws SQLException {
        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals(code) && issue.subjectId().equals(subjectId)));
    }

    private void withReplicationTriggersDisabled(SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static SkillProgressionDefinition curve(SkillId skillId) {
        ArrayList<Long> cumulative = new ArrayList<>(101);
        for (int level = 0; level <= 100; level++) cumulative.add((long) level * level * 100L);
        return new SkillProgressionDefinition(skillId, cumulative);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }

    private record ActiveContext(BountyContractSnapshot readyContract, BountySummonLeaseResult summon) { }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("test clock supports UTC only");
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
