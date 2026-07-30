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
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceDefinition;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ResourceSourceLiveCatalogIntegrityVerifierIntegrationTest {
    private static final String ZONE = "verify_resources";
    private static final String TEMPLATE = "resources-v1";
    private static final String DEFINITION = "verify.resource.live";
    private static final String OTHER_DEFINITION = "verify.resource.other";
    private static final String DROP = "verify.resource_drop";
    private static final SkillId SKILL = new SkillId("mining");

    private Database database;
    private DataSource dataSource;
    private ItemCatalog items;
    private SkillProgressionCatalog skills;

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
        items = new ItemCatalog(List.of(new ItemDefinition(
                DROP,
                "IRON_INGOT",
                "Verifier Resource",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
        skills = new SkillProgressionCatalog(List.of(curve(SKILL)));
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE resource_sources, zone_instances, backends RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void matchingRunnableSourceProducesNoIssue() throws Exception {
        insertSource("ACTIVE", DEFINITION, ZONE, TEMPLATE);

        assertTrue(verifier(catalog(definition(DEFINITION, ZONE, TEMPLATE))).verify(100).isEmpty());
    }

    @Test
    void missingRunnableDefinitionIsCritical() throws Exception {
        insertSource("STARTING", DEFINITION, ZONE, TEMPLATE);

        List<IntegrityIssue> issues = verifier(catalog(definition(OTHER_DEFINITION, ZONE, TEMPLATE))).verify(100);

        assertEquals(1, issues.size());
        assertEquals(IntegritySeverity.CRITICAL, issues.getFirst().severity());
        assertEquals("RESOURCE_SOURCE_LIVE_CONTENT_MISMATCH", issues.getFirst().code());
        assertEquals(DEFINITION + "@" + ZONE + "/" + TEMPLATE, issues.getFirst().subjectId());
    }

    @Test
    void runnableZoneOrTemplateDriftIsCriticalButBalanceChangesRemainAllowed() throws Exception {
        insertSource("ACTIVE", DEFINITION, ZONE, TEMPLATE);
        ResourceSourceDefinition retuned = new ResourceSourceDefinition(
                DEFINITION,
                ZONE,
                TEMPLATE,
                DROP,
                99,
                SKILL,
                999,
                Duration.ofHours(2)
        );
        assertTrue(verifier(catalog(retuned)).verify(100).isEmpty());

        ResourceSourceDefinition moved = definition(DEFINITION, "other_zone", TEMPLATE);
        assertEquals(
                "RESOURCE_SOURCE_LIVE_CONTENT_MISMATCH",
                verifier(catalog(moved)).verify(100).getFirst().code()
        );
    }

    @Test
    void stoppedHistoricalSourceDoesNotPinRemovedDefinition() throws Exception {
        insertSource("STOPPED", DEFINITION, ZONE, TEMPLATE);

        assertTrue(verifier(catalog(definition(OTHER_DEFINITION, ZONE, TEMPLATE))).verify(100).isEmpty());
    }

    private ResourceSourceLiveCatalogIntegrityVerifier verifier(ResourceSourceCatalog catalog) {
        return new ResourceSourceLiveCatalogIntegrityVerifier(dataSource, catalog);
    }

    private ResourceSourceCatalog catalog(ResourceSourceDefinition... definitions) {
        return new ResourceSourceCatalog(Arrays.asList(definitions), items, skills);
    }

    private static ResourceSourceDefinition definition(String id, String zone, String template) {
        return new ResourceSourceDefinition(
                id,
                zone,
                template,
                DROP,
                1,
                SKILL,
                10,
                Duration.ofSeconds(5)
        );
    }

    private void insertSource(String status, String definitionId, String zone, String template) throws SQLException {
        UUID instanceId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement backend = connection.prepareStatement(
                    "INSERT INTO backends(backend_id, status) VALUES ('paper-a', 'ONLINE') ON CONFLICT DO NOTHING")) {
                backend.executeUpdate();
            }
            try (PreparedStatement instance = connection.prepareStatement("""
                    INSERT INTO zone_instances(
                        instance_id, zone_id, template_version, backend_id, status,
                        player_count, soft_capacity, hard_capacity
                    ) VALUES (?, ?, ?, 'paper-a', ?, 0, 20, 30)
                    """)) {
                instance.setObject(1, instanceId);
                instance.setString(2, zone);
                instance.setString(3, template);
                instance.setString(4, status);
                instance.executeUpdate();
            }
            try (PreparedStatement source = connection.prepareStatement("""
                    INSERT INTO resource_sources(
                        source_id, instance_id, source_key, definition_id, cycle_no, state_version
                    ) VALUES (?, ?, ?, ?, 0, 0)
                    """)) {
                source.setObject(1, UUID.randomUUID());
                source.setObject(2, instanceId);
                source.setString(3, "source." + UUID.randomUUID().toString().replace("-", ""));
                source.setString(4, definitionId);
                source.executeUpdate();
            }
        }
    }

    private static SkillProgressionDefinition curve(SkillId skillId) {
        ArrayList<Long> thresholds = new ArrayList<>(101);
        for (int level = 0; level <= 100; level++) thresholds.add((long) level * level * 100L);
        return new SkillProgressionDefinition(skillId, thresholds);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
