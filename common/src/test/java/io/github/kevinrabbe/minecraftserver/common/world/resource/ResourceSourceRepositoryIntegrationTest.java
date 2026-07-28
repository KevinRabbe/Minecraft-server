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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ResourceSourceRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String ZONE = "starter_mine";
    private static final String TEMPLATE = "mine-v1";
    private static final String ORE = "starter.iron_ore";
    private static final SkillId MINING = new SkillId("mining");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ResourceSourceRepository sources;
    private ResourceHarvestFulfillmentRepository fulfillments;

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

        ItemCatalog items = new ItemCatalog(List.of(
                new ItemDefinition(
                        ORE,
                        "RAW_IRON",
                        "Starter Iron Ore",
                        64,
                        ItemCategory.MATERIALS,
                        ItemIdentityKind.COMMODITY
                )
        ));
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(linearSkill(MINING)));
        ResourceSourceCatalog sourceCatalog = new ResourceSourceCatalog(
                List.of(new ResourceSourceDefinition(
                        "starter.mine.iron",
                        ZONE,
                        TEMPLATE,
                        ORE,
                        2,
                        MINING,
                        25,
                        Duration.ofHours(1)
                )),
                items,
                skills
        );
        sources = new ResourceSourceRepository(dataSource, sourceCatalog);
        fulfillments = new ResourceHarvestFulfillmentRepository(dataSource, skills);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
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
    void sourceRegistrationRequiresExactZoneTemplateAndIsIdempotent() throws Exception {
        UUID instance = createInstance(ZONE, TEMPLATE, "ACTIVE");

        ResourceSourceSnapshot first = sources.ensureSource(instance, "iron.01", "starter.mine.iron");
        ResourceSourceSnapshot retry = sources.ensureSource(instance, "iron.01", "starter.mine.iron");

        assertEquals(first, retry);
        assertEquals(0L, first.cycleNo());
        assertEquals(1L, rowCount("resource_sources"));

        UUID wrongTemplate = createInstance(ZONE, "mine-v2", "ACTIVE");
        assertThrows(
                ResourceSourceException.class,
                () -> sources.ensureSource(wrongTemplate, "iron.01", "starter.mine.iron")
        );
    }

    @Test
    void harvestConsumesOneCycleExactlyOnceAndCooldownBlocksSecondOperation() throws Exception {
        UUID instance = createInstance(ZONE, TEMPLATE, "ACTIVE");
        PlayerContext player = playerInInstance("MinerA", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "iron.01", "starter.mine.iron");
        UUID operationId = UUID.randomUUID();

        ResourceHarvestEntitlement first = sources.harvest(
                operationId,
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                source.sourceId(),
                "resource.harvest"
        );
        ResourceHarvestEntitlement retry = sources.harvest(
                operationId,
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                source.sourceId(),
                "resource.harvest"
        );

        assertEquals(first, retry);
        assertEquals(0L, first.sourceCycleNo());
        assertEquals(ORE, first.commodityDefinitionId());
        assertEquals(2L, first.commodityQuantity());
        assertEquals(MINING, first.skillId());
        assertEquals(25L, first.requestedExperience());
        assertEquals(1L, sources.loadSource(source.sourceId()).cycleNo());
        assertEquals(1L, rowCount("resource_harvests"));

        assertThrows(
                ResourceSourceException.class,
                () -> sources.harvest(
                        UUID.randomUUID(),
                        player.session().sessionId(),
                        "paper-a",
                        player.session().stateVersion(),
                        source.sourceId(),
                        "resource.harvest"
                )
        );
    }

    @Test
    void playerAttachedToAnotherInstanceCannotConsumeSource() throws Exception {
        UUID sourceInstance = createInstance(ZONE, TEMPLATE, "ACTIVE");
        UUID otherInstance = createInstance(ZONE, TEMPLATE, "ACTIVE");
        PlayerContext player = playerInInstance("MinerB", otherInstance);
        ResourceSourceSnapshot source = sources.ensureSource(sourceInstance, "iron.01", "starter.mine.iron");

        assertThrows(
                ResourceSourceException.class,
                () -> sources.harvest(
                        UUID.randomUUID(),
                        player.session().sessionId(),
                        "paper-a",
                        player.session().stateVersion(),
                        source.sourceId(),
                        "resource.harvest"
                )
        );
        assertEquals(0L, sources.loadSource(source.sourceId()).cycleNo());
        assertEquals(0L, rowCount("resource_harvests"));
    }

    @Test
    void concurrentHarvestsOfOneCycleHaveExactlyOneWinner() throws Exception {
        UUID instance = createInstance(ZONE, TEMPLATE, "ACTIVE");
        PlayerContext player = playerInInstance("MinerRace", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "iron.01", "starter.mine.iron");

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ResourceHarvestEntitlement> first = executor.submit(() -> sources.harvest(
                    UUID.randomUUID(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                    source.sourceId(), "resource.harvest"
            ));
            Future<ResourceHarvestEntitlement> second = executor.submit(() -> sources.harvest(
                    UUID.randomUUID(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                    source.sourceId(), "resource.harvest"
            ));
            for (Future<ResourceHarvestEntitlement> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof ResourceSourceException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1L, rowCount("resource_harvests"));
        assertEquals(1L, sources.loadSource(source.sourceId()).cycleNo());
    }

    @Test
    void harvestFulfillmentCreatesOneCommodityDeliveryAndOneXpAwardAndDrainsRecoveryQueue() throws Exception {
        UUID instance = createInstance(ZONE, TEMPLATE, "ACTIVE");
        PlayerContext player = playerInInstance("MinerFulfill", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "iron.01", "starter.mine.iron");
        ResourceHarvestEntitlement harvest = sources.harvest(
                UUID.randomUUID(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                source.sourceId(), "resource.harvest"
        );

        assertEquals(List.of(harvest), fulfillments.listUnfulfilled(10));
        ResourceHarvestFulfillmentResult first = fulfillments.fulfill(harvest.harvestId());
        ResourceHarvestFulfillmentResult retry = fulfillments.fulfill(harvest.harvestId());

        assertEquals(first, retry);
        assertEquals(harvest, first.entitlement());
        assertEquals(25L, first.experienceAward().requestedExperience());
        assertEquals(25L, first.experienceAward().grantedExperience());
        assertEquals(25L, playerSkillExperience(player.playerId(), MINING));
        assertPendingCommodity(first.commodityDeliveryId(), player.playerId(), ORE, 2);
        assertEquals(1L, rowCount("resource_harvest_fulfillments"));
        assertTrue(fulfillments.listUnfulfilled(10).isEmpty());
    }

    @Test
    void harvestEvidenceIsAppendOnly() throws Exception {
        UUID instance = createInstance(ZONE, TEMPLATE, "ACTIVE");
        PlayerContext player = playerInInstance("MinerAudit", instance);
        ResourceSourceSnapshot source = sources.ensureSource(instance, "iron.01", "starter.mine.iron");
        ResourceHarvestEntitlement harvest = sources.harvest(
                UUID.randomUUID(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                source.sourceId(), "resource.harvest"
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE resource_harvests
                     SET commodity_quantity = commodity_quantity + 1
                     WHERE harvest_id = ?
                     """)) {
            statement.setObject(1, harvest.harvestId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private UUID createInstance(String zoneId, String templateVersion, String status) throws SQLException {
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
                    ) VALUES (?, ?, ?, 'paper-a', ?, 0, 20, 30)
                    """)) {
                instance.setObject(1, instanceId);
                instance.setString(2, zoneId);
                instance.setString(3, templateVersion);
                instance.setString(4, status);
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

    private void assertPendingCommodity(
            UUID deliveryId,
            UUID playerId,
            String definitionId,
            long quantity
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id, commodity_definition_id, quantity, status
                     FROM pending_commodity_deliveries
                     WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, deliveryId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(playerId, row.getObject(1, UUID.class));
                assertEquals(definitionId, row.getString(2));
                assertEquals(quantity, row.getLong(3));
                assertEquals("PENDING", row.getString(4));
            }
        }
    }

    private long playerSkillExperience(UUID playerId, SkillId skillId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT experience FROM player_skills WHERE player_id = ? AND skill_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, skillId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return 0;
                return row.getLong(1);
            }
        }
    }

    private long rowCount(String table) throws SQLException {
        if (!List.of(
                "resource_sources", "resource_harvests", "resource_harvest_fulfillments"
        ).contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static SkillProgressionDefinition linearSkill(SkillId skillId) {
        ArrayList<Long> thresholds = new ArrayList<>();
        for (int level = 0; level <= 100; level++) {
            thresholds.add(level * 100L);
        }
        return new SkillProgressionDefinition(skillId, thresholds);
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
