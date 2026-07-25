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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ResourceEntitySpawnRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String ZONE = "starter_pve";
    private static final String TEMPLATE = "pve-v1";
    private static final String DROP = "starter.rotten_flesh";
    private static final String SOURCE_DEFINITION = "starter.pve.zombie";
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
                "Rotten Flesh",
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
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void entityBoundSourceCannotBeHarvestedWithoutExactKillClaim() throws Exception {
        UUID instance = createInstance();
        PlayerContext player = playerInInstance("MobDirect", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "zombie.01", SOURCE_DEFINITION);
        entitySpawns.ensureEntitySource(source.sourceId());

        assertThrows(
                SQLException.class,
                () -> sources.harvest(
                        UUID.randomUUID(),
                        player.session().sessionId(),
                        "paper-a",
                        player.session().stateVersion(),
                        source.sourceId(),
                        "resource.entity_kill"
                )
        );
        assertEquals(0L, sources.loadSource(source.sourceId()).cycleNo());
        assertEquals(0L, rowCount("resource_harvests"));
    }

    @Test
    void confirmedEntityKillConsumesExactCycleAndFulfillsRewardOnce() throws Exception {
        UUID instance = createInstance();
        PlayerContext player = playerInInstance("MobKiller", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "zombie.01", SOURCE_DEFINITION);
        entitySpawns.ensureEntitySource(source.sourceId());

        ResourceEntitySpawnSnapshot pending = entitySpawns.reserveSpawn(
                source.sourceId(), Duration.ofSeconds(5)
        ).orElseThrow();
        UUID entityUuid = UUID.randomUUID();
        ResourceEntitySpawnSnapshot active = entitySpawns.confirmSpawn(
                pending.spawnId(), entityUuid, Duration.ofMinutes(5)
        );
        assertEquals(ResourceEntitySpawnStatus.ACTIVE, active.status());

        ResourceEntityKillClaim claim = entitySpawns.prepareKillClaim(active.spawnId(), entityUuid);
        ResourceHarvestFulfillmentResult first = gathering.harvestAndFulfill(
                claim.operationId(),
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                source.sourceId(),
                "resource.entity_kill"
        );
        ResourceHarvestFulfillmentResult retry = gathering.harvestAndFulfill(
                claim.operationId(),
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                source.sourceId(),
                "resource.entity_kill"
        );

        assertEquals(first, retry);
        assertEquals(claim, entitySpawns.prepareKillClaim(active.spawnId(), entityUuid));
        ResourceEntitySpawnSnapshot killed = entitySpawns.loadSpawn(active.spawnId()).orElseThrow();
        assertEquals(ResourceEntitySpawnStatus.KILLED, killed.status());
        assertEquals(player.playerId(), killed.killerPlayerId());
        assertEquals(1L, sources.loadSource(source.sourceId()).cycleNo());
        assertEquals(1L, rowCount("resource_harvests"));
        assertEquals(1L, rowCount("resource_harvest_fulfillments"));
        assertEquals(10L, playerSkillExperience(player.playerId()));
        assertPendingCommodity(first.commodityDeliveryId(), player.playerId(), 1L);
    }

    @Test
    void staleKilledEntityCannotConsumeTheNextSourceCycle() throws Exception {
        UUID instance = createInstance();
        PlayerContext player = playerInInstance("MobStale", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "zombie.01", SOURCE_DEFINITION);
        entitySpawns.ensureEntitySource(source.sourceId());

        ResourceEntitySpawnSnapshot first = entitySpawns.reserveSpawn(
                source.sourceId(), Duration.ofSeconds(5)
        ).orElseThrow();
        UUID firstEntity = UUID.randomUUID();
        entitySpawns.confirmSpawn(first.spawnId(), firstEntity, Duration.ofMinutes(5));
        ResourceEntityKillClaim oldClaim = entitySpawns.prepareKillClaim(first.spawnId(), firstEntity);
        gathering.harvestAndFulfill(
                oldClaim.operationId(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                source.sourceId(), "resource.entity_kill"
        );

        Thread.sleep(10L);
        ResourceEntitySpawnSnapshot second = entitySpawns.reserveSpawn(
                source.sourceId(), Duration.ofSeconds(5)
        ).orElseThrow();
        assertEquals(1L, second.sourceCycleNo());
        assertNotEquals(first.spawnId(), second.spawnId());
        UUID secondEntity = UUID.randomUUID();
        entitySpawns.confirmSpawn(second.spawnId(), secondEntity, Duration.ofMinutes(5));

        ResourceHarvestFulfillmentResult replay = gathering.harvestAndFulfill(
                oldClaim.operationId(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                source.sourceId(), "resource.entity_kill"
        );
        assertEquals(0L, replay.entitlement().sourceCycleNo());
        assertEquals(1L, sources.loadSource(source.sourceId()).cycleNo());
        assertEquals(ResourceEntitySpawnStatus.ACTIVE, entitySpawns.loadSpawn(second.spawnId()).orElseThrow().status());
        assertEquals(1L, rowCount("resource_harvests"));
    }

    @Test
    void environmentalDeathAdvancesCycleWithoutReward() throws Exception {
        UUID instance = createInstance();
        ResourceSourceSnapshot source = sources.ensureSource(instance, "zombie.01", SOURCE_DEFINITION);
        entitySpawns.ensureEntitySource(source.sourceId());

        ResourceEntitySpawnSnapshot pending = entitySpawns.reserveSpawn(
                source.sourceId(), Duration.ofSeconds(5)
        ).orElseThrow();
        UUID entityUuid = UUID.randomUUID();
        entitySpawns.confirmSpawn(pending.spawnId(), entityUuid, Duration.ofMinutes(5));
        ResourceEntitySpawnSnapshot cancelled = entitySpawns.resolveWithoutReward(pending.spawnId(), entityUuid);

        assertEquals(ResourceEntitySpawnStatus.CANCELLED, cancelled.status());
        assertEquals(1L, sources.loadSource(source.sourceId()).cycleNo());
        assertEquals(0L, rowCount("resource_harvests"));
        assertThrows(
                ResourceSourceException.class,
                () -> entitySpawns.prepareKillClaim(pending.spawnId(), entityUuid)
        );
    }

    @Test
    void expiredPendingReservationRecoversByAdvancingSourceCycle() throws Exception {
        UUID instance = createInstance();
        ResourceSourceSnapshot source = sources.ensureSource(instance, "zombie.01", SOURCE_DEFINITION);
        entitySpawns.ensureEntitySource(source.sourceId());

        ResourceEntitySpawnSnapshot pending = entitySpawns.reserveSpawn(
                source.sourceId(), Duration.ofMillis(5)
        ).orElseThrow();
        Thread.sleep(20L);

        assertTrue(entitySpawns.expireStaleSpawn(source.sourceId()).isEmpty());
        assertEquals(ResourceEntitySpawnStatus.EXPIRED, entitySpawns.loadSpawn(pending.spawnId()).orElseThrow().status());
        assertEquals(1L, sources.loadSource(source.sourceId()).cycleNo());
        Thread.sleep(10L);
        ResourceEntitySpawnSnapshot replacement = entitySpawns.reserveSpawn(
                source.sourceId(), Duration.ofSeconds(5)
        ).orElseThrow();
        assertEquals(1L, replacement.sourceCycleNo());
    }

    @Test
    void rawSecondKillClaimForSameSpawnIsRejected() throws Exception {
        UUID instance = createInstance();
        ResourceSourceSnapshot source = sources.ensureSource(instance, "zombie.01", SOURCE_DEFINITION);
        entitySpawns.ensureEntitySource(source.sourceId());
        ResourceEntitySpawnSnapshot pending = entitySpawns.reserveSpawn(
                source.sourceId(), Duration.ofSeconds(5)
        ).orElseThrow();
        UUID entityUuid = UUID.randomUUID();
        entitySpawns.confirmSpawn(pending.spawnId(), entityUuid, Duration.ofMinutes(5));
        entitySpawns.prepareKillClaim(pending.spawnId(), entityUuid);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO resource_entity_kill_claims(operation_id, spawn_id, entity_uuid)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, pending.spawnId());
            statement.setObject(3, entityUuid);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private UUID createInstance() throws SQLException {
        UUID instanceId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement backend = connection.prepareStatement("""
                    INSERT INTO backends(backend_id, status)
                    VALUES ('paper-a', 'ONLINE')
                    ON CONFLICT (backend_id) DO NOTHING
                    """)) {
                backend.executeUpdate();
            }
            try (PreparedStatement instance = connection.prepareStatement("""
                    INSERT INTO zone_instances(
                        instance_id, zone_id, template_version, backend_id, status,
                        player_count, soft_capacity, hard_capacity
                    ) VALUES (?, ?, ?, 'paper-a', 'ACTIVE', 0, 20, 30)
                    """)) {
                instance.setObject(1, instanceId);
                instance.setString(2, ZONE);
                instance.setString(3, TEMPLATE);
                instance.executeUpdate();
            }
        }
        return instanceId;
    }

    private PlayerContext playerInInstance(String name, UUID instanceId) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-a", instanceId, LEASE);
        return new PlayerContext(playerId, session);
    }

    private long rowCount(String table) throws SQLException {
        if (!List.of(
                "resource_harvests",
                "resource_harvest_fulfillments"
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

    private long playerSkillExperience(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT experience
                     FROM player_skills
                     WHERE player_id = ? AND skill_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, COMBAT.value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0L;
            }
        }
    }

    private void assertPendingCommodity(UUID deliveryId, UUID playerId, long quantity) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id, commodity_definition_id, quantity, status
                     FROM pending_commodity_deliveries
                     WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, deliveryId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(playerId, row.getObject("player_id", UUID.class));
                assertEquals(DROP, row.getString("commodity_definition_id"));
                assertEquals(quantity, row.getLong("quantity"));
                assertEquals("PENDING", row.getString("status"));
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

    private record PlayerContext(UUID playerId, SessionLease session) { }
}
