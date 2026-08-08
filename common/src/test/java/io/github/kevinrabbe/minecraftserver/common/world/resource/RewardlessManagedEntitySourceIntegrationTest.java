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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class RewardlessManagedEntitySourceIntegrationTest {
    private static final String BACKEND = "paper-city";
    private static final String ZONE = "city";
    private static final String TEMPLATE = "city-dev-v1";
    private static final String SOURCE_DEFINITION = "starter_combat.ruinbound_champion";
    private static final Duration SESSION_LEASE = Duration.ofSeconds(30);

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
                "material.fixture",
                "FLINT",
                "Fixture",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(curve(new SkillId("combat"))));
        ResourceSourceCatalog sourceCatalog = new ResourceSourceCatalog(
                List.of(new ResourceSourceDefinition(
                        SOURCE_DEFINITION,
                        ZONE,
                        TEMPLATE,
                        null,
                        0,
                        null,
                        0,
                        Duration.ofSeconds(5)
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
    void managedEntityKillCanCommitWithoutInventingCommodityOrXpValue() throws Exception {
        UUID instanceId = createInstance();
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "RewardlessKill");
        SessionLease session = sessions.openSession(playerId, BACKEND, instanceId, SESSION_LEASE);

        ResourceSourceSnapshot source = sources.ensureSource(instanceId, "ruinbound.01", SOURCE_DEFINITION);
        entitySpawns.ensureEntitySource(source.sourceId());
        ResourceEntitySpawnSnapshot reserved = entitySpawns.reserveSpawn(
                source.sourceId(), Duration.ofSeconds(5)
        ).orElseThrow();
        UUID entityUuid = UUID.randomUUID();
        ResourceEntitySpawnSnapshot active = entitySpawns.confirmSpawn(
                reserved.spawnId(), entityUuid, Duration.ofMinutes(5)
        );
        ResourceEntityKillClaim claim = entitySpawns.prepareKillClaim(active.spawnId(), entityUuid);

        ResourceHarvestFulfillmentResult result = gathering.harvestAndFulfill(
                claim.operationId(),
                session.sessionId(),
                BACKEND,
                session.stateVersion(),
                source.sourceId(),
                "resource.entity_kill"
        );

        assertEquals(playerId, result.entitlement().playerId());
        assertNull(result.entitlement().commodityDefinitionId());
        assertEquals(0L, result.entitlement().commodityQuantity());
        assertNull(result.entitlement().skillId());
        assertEquals(0L, result.entitlement().requestedExperience());
        assertNull(result.commodityDeliveryId());
        assertNull(result.experienceAward());
        assertEquals(0L, count("pending_commodity_deliveries"));
        assertEquals(0L, count("skill_xp_awards"));
        assertEquals(0L, countLedgerForOperation(claim.operationId()));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT commodity_definition_id,
                            commodity_quantity,
                            commodity_delivery_id,
                            xp_operation_id
                     FROM resource_harvests h
                     JOIN resource_harvest_fulfillments f ON f.harvest_id = h.harvest_id
                     WHERE h.operation_id = ?
                     """)) {
            statement.setObject(1, claim.operationId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertNull(row.getString("commodity_definition_id"));
                assertEquals(0L, row.getLong("commodity_quantity"));
                assertNull(row.getObject("commodity_delivery_id", UUID.class));
                assertNull(row.getObject("xp_operation_id", UUID.class));
            }
        }

        assertTrue(new ResourceSourceIntegrityVerifier(dataSource).verify(100).isEmpty());
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
                        instance_id,
                        zone_id,
                        template_version,
                        backend_id,
                        status,
                        player_count,
                        soft_capacity,
                        hard_capacity
                    ) VALUES (?, ?, ?, ?, 'ACTIVE', 0, 20, 30)
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

    private long count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private long countLedgerForOperation(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM economic_ledger WHERE operation_id = ?
                     """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static SkillProgressionDefinition curve(SkillId skillId) {
        ArrayList<Long> thresholds = new ArrayList<>();
        for (int level = 0; level <= 100; level++) thresholds.add(level * 100L);
        return new SkillProgressionDefinition(skillId, thresholds);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
