package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceStatus;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
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
class ResourceSourceLiveContentCompatibilityValidatorIntegrationTest {
    private static final String BACKEND = "paper-resource-compat";
    private static final String ZONE = "mine";
    private static final String TEMPLATE = "mine-v1";
    private static final String DEFINITION = "resource.iron_ore";
    private static final String OTHER_DEFINITION = "resource.other";
    private static final String COMMODITY = "material.raw_iron";
    private static final SkillId MINING = new SkillId("mining");

    private Database database;
    private DataSource dataSource;
    private BackendRegistry backends;
    private ZoneInstanceRegistry instances;
    private ResourceSourceCatalog originalCatalog;
    private ResourceSourceCatalog replacementCatalog;

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
        backends = new BackendRegistry(dataSource);
        instances = new ZoneInstanceRegistry(dataSource);

        ItemCatalog items = new ItemCatalog(List.of(new ItemDefinition(
                COMMODITY,
                "RAW_IRON",
                "Raw Iron",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(curve(MINING)));
        originalCatalog = catalog(items, skills, definition(DEFINITION, ZONE, TEMPLATE));
        replacementCatalog = catalog(items, skills, definition(OTHER_DEFINITION, ZONE, TEMPLATE));
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE backends RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void liveSourceRequiresStableDefinitionAndZoneTemplate() throws Exception {
        UUID instanceId = activeInstance();
        ResourceSourceRepository sources = new ResourceSourceRepository(dataSource, originalCatalog);
        sources.ensureSource(instanceId, "iron.01", DEFINITION);

        assertDoesNotThrow(() -> ResourceSourceLiveContentCompatibilityValidator.validate(dataSource, originalCatalog));
        assertThrows(
                ResourceSourceException.class,
                () -> ResourceSourceLiveContentCompatibilityValidator.validate(dataSource, replacementCatalog)
        );

        ResourceSourceCatalog moved = catalog(
                itemCatalog(),
                skillCatalog(),
                definition(DEFINITION, "other_mine", "other-v1")
        );
        assertThrows(
                ResourceSourceException.class,
                () -> ResourceSourceLiveContentCompatibilityValidator.validate(dataSource, moved)
        );
    }

    @Test
    void stoppedHistoricalSourceMayRetireAfterUnresolvedEntityCycleIsClosed() throws Exception {
        UUID instanceId = activeInstance();
        ResourceSourceRepository sources = new ResourceSourceRepository(dataSource, originalCatalog);
        ResourceSourceSnapshot source = sources.ensureSource(instanceId, "zombie.01", DEFINITION);
        ResourceEntitySpawnRepository spawns = new ResourceEntitySpawnRepository(dataSource, originalCatalog);
        spawns.ensureEntitySource(source.sourceId());
        ResourceEntitySpawnSnapshot pending = spawns.reserveSpawn(
                source.sourceId(),
                Duration.ofMinutes(1)
        ).orElseThrow();
        instances.markStopped(instanceId);

        assertThrows(
                ResourceSourceException.class,
                () -> ResourceSourceLiveContentCompatibilityValidator.validate(dataSource, replacementCatalog)
        );

        spawns.cancelPending(pending.spawnId());
        assertDoesNotThrow(
                () -> ResourceSourceLiveContentCompatibilityValidator.validate(dataSource, replacementCatalog)
        );
    }

    private UUID activeInstance() throws SQLException {
        backends.registerOnline(BACKEND, 0);
        UUID instanceId = UUID.randomUUID();
        instances.registerStarting(instanceId, ZONE, TEMPLATE, BACKEND, 20, 30);
        instances.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, 0);
        return instanceId;
    }

    private static ResourceSourceCatalog catalog(
            ItemCatalog items,
            SkillProgressionCatalog skills,
            ResourceSourceDefinition definition
    ) {
        return new ResourceSourceCatalog(List.of(definition), items, skills);
    }

    private static ResourceSourceDefinition definition(String id, String zone, String template) {
        return new ResourceSourceDefinition(
                id,
                zone,
                template,
                COMMODITY,
                1,
                MINING,
                10,
                Duration.ofSeconds(30)
        );
    }

    private static ItemCatalog itemCatalog() {
        return new ItemCatalog(List.of(new ItemDefinition(
                COMMODITY,
                "RAW_IRON",
                "Raw Iron",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
    }

    private static SkillProgressionCatalog skillCatalog() {
        return new SkillProgressionCatalog(List.of(curve(MINING)));
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
}
