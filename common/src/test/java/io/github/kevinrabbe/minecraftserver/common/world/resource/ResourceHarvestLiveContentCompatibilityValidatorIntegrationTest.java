package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogException;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ResourceHarvestLiveContentCompatibilityValidatorIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String ZONE = "starter_mine";
    private static final String TEMPLATE = "mine-v1";
    private static final String SOURCE_DEFINITION = "starter.mine.iron";
    private static final String NO_XP_SOURCE_DEFINITION = "starter.mine.iron_no_xp";
    private static final String COMMODITY = "starter.iron_ore";
    private static final String OTHER_COMMODITY = "starter.copper_ore";
    private static final SkillId MINING = new SkillId("mining");
    private static final SkillId WOODCUTTING = new SkillId("woodcutting");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ResourceSourceRepository sources;
    private ResourceHarvestFulfillmentRepository fulfillments;
    private ItemCatalog originalItems;
    private ItemCatalog retunedItems;
    private ItemCatalog missingItems;
    private ItemCatalog nonCommodityItems;
    private SkillProgressionCatalog originalSkills;
    private SkillProgressionCatalog retunedSkills;
    private SkillProgressionCatalog missingSkills;

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

        originalItems = itemCatalog(commodity(COMMODITY, "RAW_IRON", "Starter Iron Ore", 64));
        retunedItems = itemCatalog(commodity(COMMODITY, "IRON_NUGGET", "Retuned Iron Ore", 2));
        missingItems = itemCatalog(commodity(OTHER_COMMODITY, "RAW_COPPER", "Starter Copper Ore", 64));
        nonCommodityItems = itemCatalog(new ItemDefinition(
                COMMODITY,
                "IRON_SWORD",
                "Wrong Identity",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        ));

        originalSkills = skillCatalog(linearSkill(MINING, 100L));
        retunedSkills = skillCatalog(linearSkill(MINING, 250L));
        missingSkills = skillCatalog(linearSkill(WOODCUTTING, 100L));

        ResourceSourceCatalog sourceCatalog = new ResourceSourceCatalog(
                List.of(
                        new ResourceSourceDefinition(
                                SOURCE_DEFINITION,
                                ZONE,
                                TEMPLATE,
                                COMMODITY,
                                20,
                                MINING,
                                25,
                                Duration.ofHours(1)
                        ),
                        new ResourceSourceDefinition(
                                NO_XP_SOURCE_DEFINITION,
                                ZONE,
                                TEMPLATE,
                                COMMODITY,
                                20,
                                null,
                                0,
                                Duration.ofHours(1)
                        )
                ),
                originalItems,
                originalSkills
        );
        sources = new ResourceSourceRepository(dataSource, sourceCatalog);
        fulfillments = new ResourceHarvestFulfillmentRepository(dataSource, originalSkills);
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
    void unfulfilledHarvestPinsCommodityAndSkillUntilFulfillmentEvidenceExists() throws Exception {
        ResourceHarvestEntitlement harvest = createUnfulfilledHarvest("RecoveryMiner", true);

        assertDoesNotThrow(() -> validate(originalItems, originalSkills));
        assertDoesNotThrow(() -> validate(retunedItems, retunedSkills));
        assertThrows(ItemCatalogException.class, () -> validate(missingItems, originalSkills));
        assertThrows(ItemCatalogException.class, () -> validate(nonCommodityItems, originalSkills));
        assertThrows(ResourceSourceException.class, () -> validate(originalItems, missingSkills));

        fulfillments.fulfill(harvest.harvestId());

        assertDoesNotThrow(() -> validate(missingItems, missingSkills));
    }

    @Test
    void harvestWithoutXpDoesNotPinAnySkillDefinition() throws Exception {
        createUnfulfilledHarvest("NoXpMiner", false);

        assertDoesNotThrow(() -> validate(originalItems, missingSkills));
        assertThrows(ItemCatalogException.class, () -> validate(missingItems, missingSkills));
    }

    private ResourceHarvestEntitlement createUnfulfilledHarvest(String playerName, boolean withSkill)
            throws SQLException {
        UUID instanceId = createInstance();
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), playerName);
        SessionLease session = sessions.openSession(playerId, "paper-a", instanceId, LEASE);
        String definitionId = withSkill ? SOURCE_DEFINITION : NO_XP_SOURCE_DEFINITION;
        String sourceKey = withSkill ? "iron.xp" : "iron.no_xp";
        ResourceSourceSnapshot source = sources.ensureSource(instanceId, sourceKey, definitionId);
        return sources.harvest(
                UUID.randomUUID(),
                session.sessionId(),
                "paper-a",
                session.stateVersion(),
                source.sourceId(),
                "resource.harvest"
        );
    }

    private UUID createInstance() throws SQLException {
        UUID instanceId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO backends(backend_id, status)
                    VALUES ('paper-a', 'ONLINE')
                    """)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO zone_instances(
                        instance_id,
                        zone_id,
                        template_version,
                        backend_id,
                        status,
                        player_count,
                        soft_capacity,
                        hard_capacity
                    ) VALUES (?, ?, ?, 'paper-a', 'ACTIVE', 0, 20, 30)
                    """)) {
                statement.setObject(1, instanceId);
                statement.setString(2, ZONE);
                statement.setString(3, TEMPLATE);
                statement.executeUpdate();
            }
        }
        return instanceId;
    }

    private void validate(ItemCatalog itemCatalog, SkillProgressionCatalog skillCatalog) throws SQLException {
        ResourceHarvestLiveContentCompatibilityValidator.validate(dataSource, itemCatalog, skillCatalog);
    }

    private static ItemCatalog itemCatalog(ItemDefinition definition) {
        return new ItemCatalog(List.of(definition));
    }

    private static ItemDefinition commodity(String id, String material, String displayName, int maxStack) {
        return new ItemDefinition(
                id,
                material,
                displayName,
                maxStack,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        );
    }

    private static SkillProgressionCatalog skillCatalog(SkillProgressionDefinition definition) {
        return new SkillProgressionCatalog(List.of(definition));
    }

    private static SkillProgressionDefinition linearSkill(SkillId skillId, long experiencePerLevel) {
        ArrayList<Long> thresholds = new ArrayList<>();
        for (int level = 0; level <= SkillProgressionDefinition.LONG_TERM_MAX_LEVEL; level++) {
            thresholds.add(level * experiencePerLevel);
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
}
