package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import io.github.kevinrabbe.minecraftserver.common.verification.IntegrityIssue;
import io.github.kevinrabbe.minecraftserver.common.verification.PersistentIntegrityVerifier;
import io.github.kevinrabbe.minecraftserver.common.verification.ResourceSourceIntegrityVerifier;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * High-cardinality authority/load proof for managed ordinary-PvE entities.
 *
 * <p>This is deliberately not a Paper TPS or latency benchmark. It creates database contention across more concurrent
 * workers than the test connection pool, drives two complete rewarded entity cycles per source, and then requires both
 * resource-specific and aggregate persistent integrity to remain clean.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ResourceEntityLifecycleLoadIntegrationTest {
    private static final int SOURCE_COUNT = 64;
    private static final int WORKER_COUNT = 16;
    private static final int CYCLES = 2;
    private static final Duration SESSION_LEASE = Duration.ofMinutes(5);
    private static final Duration SPAWN_LEASE = Duration.ofSeconds(10);
    private static final Duration ACTIVE_LIFETIME = Duration.ofMinutes(5);
    private static final String BACKEND = "paper-load";
    private static final String ZONE = "starter_pve";
    private static final String TEMPLATE = "pve-v1";
    private static final String DROP = "starter.load_flesh";
    private static final String SOURCE_DEFINITION = "starter.pve.load_zombie";
    private static final SkillId COMBAT = new SkillId("combat");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ResourceSourceRepository sources;
    private ResourceEntitySpawnRepository entitySpawns;
    private ResourceGatheringService gathering;

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

        ItemCatalog items = new ItemCatalog(List.of(new ItemDefinition(
                DROP,
                "ROTTEN_FLESH",
                "Load Test Flesh",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(curve(COMBAT)));
        ResourceSourceCatalog sourceCatalog = new ResourceSourceCatalog(
                List.of(new ResourceSourceDefinition(
                        SOURCE_DEFINITION,
                        ZONE,
                        TEMPLATE,
                        DROP,
                        1,
                        COMBAT,
                        10,
                        Duration.ofMillis(1)
                )),
                items,
                skills
        );
        sources = new ResourceSourceRepository(dataSource, sourceCatalog);
        entitySpawns = new ResourceEntitySpawnRepository(dataSource, sourceCatalog);
        gathering = new ResourceGatheringService(
                sources,
                new ResourceHarvestFulfillmentRepository(dataSource, skills)
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
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
                        player_state,
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
            // Other progression integration tests may have advanced this global singleton before this class runs.
            // Since processed_operations is intentionally reset above, restore the matching canonical launch-cap evidence.
            statement.execute("""
                    UPDATE progression_state
                    SET active_skill_cap = 50,
                        state_version = 0,
                        source_operation_id = NULL,
                        changed_at = NOW()
                    WHERE singleton = TRUE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void concurrentIndependentEntityCyclesRemainExactlyOnceAndIntegrityClean() throws Exception {
        UUID instanceId = createInstance();
        List<LoadSource> loadSources = new ArrayList<>(SOURCE_COUNT);
        for (int index = 0; index < SOURCE_COUNT; index++) {
            UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "Load" + index);
            SessionLease session = sessions.openSession(playerId, BACKEND, instanceId, SESSION_LEASE);
            ResourceSourceSnapshot source = sources.ensureSource(
                    instanceId,
                    "zombie.load." + index,
                    SOURCE_DEFINITION
            );
            entitySpawns.ensureEntitySource(source.sourceId());
            loadSources.add(new LoadSource(playerId, session, source.sourceId()));
        }

        for (int cycle = 0; cycle < CYCLES; cycle++) {
            if (cycle > 0) {
                Thread.sleep(20L); // Only clears the configured 1 ms respawn delay; this is not a performance assertion.
            }
            runRewardedWave(loadSources, cycle);
        }

        long expectedOperations = (long) SOURCE_COUNT * CYCLES;
        assertEquals(expectedOperations, countRows("resource_entity_kill_claims"));
        assertEquals(expectedOperations, countRows("resource_harvests"));
        assertEquals(expectedOperations, countRows("resource_harvest_fulfillments"));
        assertEquals(expectedOperations, countRows("pending_commodity_deliveries"));
        assertEquals(expectedOperations, countSpawnsByStatus("KILLED"));
        assertEquals(0L, countSpawnsByStatus("PENDING"));
        assertEquals(0L, countSpawnsByStatus("ACTIVE"));
        assertEquals(SOURCE_COUNT, countSourcesAtCycle(CYCLES));
        assertEquals(SOURCE_COUNT, countPlayersAtCombatExperience(CYCLES * 10L));

        List<IntegrityIssue> resourceIssues = new ResourceSourceIntegrityVerifier(dataSource).verify(1_000);
        assertTrue(resourceIssues.isEmpty(), resourceIssues.toString());
        List<IntegrityIssue> aggregateIssues = new PersistentIntegrityVerifier(dataSource).verify(1_000);
        assertTrue(aggregateIssues.isEmpty(), aggregateIssues.toString());
    }

    private void runRewardedWave(List<LoadSource> loadSources, long expectedCycle) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(WORKER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>(loadSources.size());
        try {
            for (LoadSource context : loadSources) {
                futures.add(workers.submit(() -> {
                    start.await();
                    ResourceEntitySpawnSnapshot pending = entitySpawns.reserveSpawn(
                            context.sourceId(),
                            SPAWN_LEASE
                    ).orElseThrow();
                    assertEquals(expectedCycle, pending.sourceCycleNo());

                    UUID entityUuid = UUID.randomUUID();
                    ResourceEntitySpawnSnapshot active = entitySpawns.confirmSpawn(
                            pending.spawnId(),
                            entityUuid,
                            ACTIVE_LIFETIME
                    );
                    assertEquals(ResourceEntitySpawnStatus.ACTIVE, active.status());

                    ResourceEntityKillClaim claim = entitySpawns.prepareKillClaim(active.spawnId(), entityUuid);
                    ResourceHarvestFulfillmentResult fulfilled = gathering.harvestAndFulfill(
                            claim.operationId(),
                            context.session().sessionId(),
                            BACKEND,
                            context.session().stateVersion(),
                            context.sourceId(),
                            "resource.entity_kill"
                    );
                    assertEquals(expectedCycle, fulfilled.entitlement().sourceCycleNo());
                    assertEquals(context.playerId(), fulfilled.entitlement().playerId());
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                // Liveness/deadlock guard only; elapsed time is not treated as a performance acceptance threshold.
                future.get(60, TimeUnit.SECONDS);
                assertFalse(future.isCancelled());
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private UUID createInstance() throws SQLException {
        UUID instanceId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement backend = connection.prepareStatement("""
                    INSERT INTO backends(backend_id, status)
                    VALUES (?, 'ONLINE')
                    """)) {
                backend.setString(1, BACKEND);
                backend.executeUpdate();
            }
            try (PreparedStatement instance = connection.prepareStatement("""
                    INSERT INTO zone_instances(
                        instance_id, zone_id, template_version, backend_id, status,
                        player_count, soft_capacity, hard_capacity
                    ) VALUES (?, ?, ?, ?, 'ACTIVE', 0, 128, 128)
                    """)) {
                instance.setObject(1, instanceId);
                instance.setString(2, ZONE);
                instance.setString(3, TEMPLATE);
                instance.setString(4, BACKEND);
                instance.executeUpdate();
            }
        }
        return instanceId;
    }

    private long countRows(String table) throws SQLException {
        if (!List.of(
                "resource_entity_kill_claims",
                "resource_harvests",
                "resource_harvest_fulfillments",
                "pending_commodity_deliveries"
        ).contains(table)) {
            throw new IllegalArgumentException("unsupported test table: " + table);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private long countSpawnsByStatus(String status) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM resource_entity_spawns
                     WHERE status = ?
                     """)) {
            statement.setString(1, status);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long countSourcesAtCycle(long cycleNo) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM resource_sources
                     WHERE cycle_no = ? AND state_version = ?
                     """)) {
            statement.setLong(1, cycleNo);
            statement.setLong(2, cycleNo);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long countPlayersAtCombatExperience(long expectedExperience) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM player_skills
                     WHERE skill_id = ? AND experience = ?
                     """)) {
            statement.setString(1, COMBAT.value());
            statement.setLong(2, expectedExperience);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static SkillProgressionDefinition curve(SkillId skillId) {
        ArrayList<Long> cumulative = new ArrayList<>(101);
        for (int level = 0; level <= 100; level++) {
            cumulative.add((long) level * level * 100L);
        }
        return new SkillProgressionDefinition(skillId, cumulative);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record LoadSource(UUID playerId, SessionLease session, UUID sourceId) { }
}
