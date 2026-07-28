package io.github.kevinrabbe.minecraftserver.common.verification;

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
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntityKillClaim;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntitySpawnRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntitySpawnSnapshot;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceGatheringService;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceHarvestEntitlement;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceHarvestFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceHarvestFulfillmentResult;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ResourceSourceIntegrityVerifierIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String ZONE = "verify_resources";
    private static final String TEMPLATE = "resources-v1";
    private static final String DROP = "verify.resource_drop";
    private static final String DIRECT_DEFINITION = "verify.resource.direct";
    private static final String ENTITY_DEFINITION = "verify.resource.entity";
    private static final SkillId COMBAT = new SkillId("combat");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ResourceSourceRepository sources;
    private ResourceEntitySpawnRepository entitySpawns;
    private ResourceGatheringService gathering;
    private ResourceSourceIntegrityVerifier verifier;

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
                "Verifier Drop",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(curve(COMBAT)));
        ResourceSourceCatalog sourceCatalog = new ResourceSourceCatalog(
                List.of(
                        new ResourceSourceDefinition(
                                DIRECT_DEFINITION,
                                ZONE,
                                TEMPLATE,
                                DROP,
                                2,
                                COMBAT,
                                10,
                                Duration.ofMillis(1)
                        ),
                        new ResourceSourceDefinition(
                                ENTITY_DEFINITION,
                                ZONE,
                                TEMPLATE,
                                DROP,
                                1,
                                COMBAT,
                                10,
                                Duration.ofMillis(1)
                        )
                ),
                items,
                skills
        );
        sources = new ResourceSourceRepository(dataSource, sourceCatalog);
        entitySpawns = new ResourceEntitySpawnRepository(dataSource, sourceCatalog);
        gathering = new ResourceGatheringService(
                sources,
                new ResourceHarvestFulfillmentRepository(dataSource, skills)
        );
        verifier = new ResourceSourceIntegrityVerifier(dataSource);
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
                        player_state,
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
    void healthyDirectAndEntityHarvestChainsProduceNoIssues() throws Exception {
        UUID instance = createInstance();
        PlayerContext directPlayer = playerInInstance("ResDirect", instance);
        ResourceSourceSnapshot direct = sources.ensureSource(instance, "direct.01", DIRECT_DEFINITION);
        gathering.harvestAndFulfill(
                UUID.randomUUID(),
                directPlayer.session().sessionId(),
                "paper-a",
                directPlayer.session().stateVersion(),
                direct.sourceId(),
                "resource.harvest"
        );

        PlayerContext entityPlayer = playerInInstance("ResEntity", instance);
        ResourceSourceSnapshot entity = sources.ensureSource(instance, "entity.01", ENTITY_DEFINITION);
        entitySpawns.ensureEntitySource(entity.sourceId());
        ResourceEntitySpawnSnapshot pending = entitySpawns.reserveSpawn(entity.sourceId(), Duration.ofSeconds(5)).orElseThrow();
        UUID entityUuid = UUID.randomUUID();
        ResourceEntitySpawnSnapshot active = entitySpawns.confirmSpawn(pending.spawnId(), entityUuid, Duration.ofMinutes(5));
        ResourceEntityKillClaim claim = entitySpawns.prepareKillClaim(active.spawnId(), entityUuid);
        gathering.harvestAndFulfill(
                claim.operationId(),
                entityPlayer.session().sessionId(),
                "paper-a",
                entityPlayer.session().stateVersion(),
                entity.sourceId(),
                "resource.entity_kill"
        );

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void unfulfilledHarvestRemainsRecoverableAndIsNotReported() throws Exception {
        UUID instance = createInstance();
        PlayerContext player = playerInInstance("ResPending", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "direct.01", DIRECT_DEFINITION);

        sources.harvest(
                UUID.randomUUID(),
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                source.sourceId(),
                "resource.harvest"
        );

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void processedHarvestEntitlementDriftIsReported() throws Exception {
        UUID instance = createInstance();
        PlayerContext player = playerInInstance("ResOpDrift", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "direct.01", DIRECT_DEFINITION);
        ResourceHarvestEntitlement harvest = sources.harvest(
                UUID.randomUUID(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                source.sourceId(), "resource.harvest"
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE processed_operations
                    SET result = jsonb_set(result, '{entitlement,commodity_quantity}', '99'::jsonb)
                    WHERE operation_id = ?
                    """)) {
                statement.setObject(1, harvest.operationId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals("RESOURCE_HARVEST_OPERATION_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(harvest.operationId().toString())));
    }

    @Test
    void fulfillmentCommodityDeliveryDriftIsReported() throws Exception {
        UUID instance = createInstance();
        PlayerContext player = playerInInstance("ResDelivery", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "direct.01", DIRECT_DEFINITION);
        ResourceHarvestFulfillmentResult result = gathering.harvestAndFulfill(
                UUID.randomUUID(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                source.sourceId(), "resource.harvest"
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE pending_commodity_deliveries
                    SET quantity = quantity + 1
                    WHERE delivery_id = ?
                    """)) {
                statement.setObject(1, result.commodityDeliveryId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals("RESOURCE_HARVEST_FULFILLMENT_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(result.entitlement().harvestId().toString())));
    }

    @Test
    void fulfillmentXpOperationDriftIsReported() throws Exception {
        UUID instance = createInstance();
        PlayerContext player = playerInInstance("ResXpDrift", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "direct.01", DIRECT_DEFINITION);
        ResourceHarvestFulfillmentResult result = gathering.harvestAndFulfill(
                UUID.randomUUID(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                source.sourceId(), "resource.harvest"
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE resource_harvest_fulfillments
                    SET xp_operation_id = ?
                    WHERE harvest_id = ?
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, result.entitlement().harvestId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals("RESOURCE_HARVEST_FULFILLMENT_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(result.entitlement().harvestId().toString())));
    }

    @Test
    void killedEntityHistoryDriftIsReported() throws Exception {
        UUID instance = createInstance();
        PlayerContext player = playerInInstance("ResKill", instance);
        UUID otherPlayer = identities.ensurePlayer(UUID.randomUUID(), "ResOther");
        ResourceSourceSnapshot source = sources.ensureSource(instance, "entity.01", ENTITY_DEFINITION);
        entitySpawns.ensureEntitySource(source.sourceId());
        ResourceEntitySpawnSnapshot pending = entitySpawns.reserveSpawn(source.sourceId(), Duration.ofSeconds(5)).orElseThrow();
        UUID entityUuid = UUID.randomUUID();
        entitySpawns.confirmSpawn(pending.spawnId(), entityUuid, Duration.ofMinutes(5));
        ResourceEntityKillClaim claim = entitySpawns.prepareKillClaim(pending.spawnId(), entityUuid);
        gathering.harvestAndFulfill(
                claim.operationId(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                source.sourceId(), "resource.entity_kill"
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE resource_entity_spawns
                    SET killer_player_id = ?
                    WHERE spawn_id = ?
                    """)) {
                statement.setObject(1, otherPlayer);
                statement.setObject(2, pending.spawnId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("RESOURCE_ENTITY_HARVEST_EVIDENCE_MISMATCH")
                        || issue.code().equals("RESOURCE_ENTITY_KILL_EVIDENCE_MISMATCH")));
    }

    @Test
    void sourceCycleVersionDriftIsReported() throws Exception {
        UUID instance = createInstance();
        ResourceSourceSnapshot source = sources.ensureSource(instance, "direct.01", DIRECT_DEFINITION);

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE resource_sources
                    SET state_version = state_version + 1
                    WHERE source_id = ?
                    """)) {
                statement.setObject(1, source.sourceId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals("RESOURCE_SOURCE_STATE_MISMATCH")
                        && issue.subjectId().equals(source.sourceId().toString())));
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

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }
}
