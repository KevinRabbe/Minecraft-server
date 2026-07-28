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
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceGatheringService;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceHarvestFulfillmentRepository;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ResourceSourceIntegrityVerifierNoXpIntegrationTest {
    private static final String ZONE = "verify_no_xp";
    private static final String TEMPLATE = "no-xp-v1";
    private static final String DROP = "verify.no_xp_drop";
    private static final String SOURCE = "verify.no_xp_source";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ResourceSourceRepository sources;
    private ResourceGatheringService gathering;
    private ResourceSourceIntegrityVerifier verifier;

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
        sessions = new PlayerSessionRepository(dataSource);

        ItemCatalog items = new ItemCatalog(List.of(new ItemDefinition(
                DROP, "FLINT", "Verifier Drop", 64, ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY
        )));
        SkillId unusedSkill = new SkillId("combat");
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(curve(unusedSkill)));
        ResourceSourceCatalog sourceCatalog = new ResourceSourceCatalog(
                List.of(new ResourceSourceDefinition(
                        SOURCE, ZONE, TEMPLATE, DROP, 1, null, 0, Duration.ofMillis(1)
                )),
                items,
                skills
        );
        sources = new ResourceSourceRepository(dataSource, sourceCatalog);
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
    void legitimateNoXpResourceHarvestReconcilesCleanly() throws Exception {
        UUID instanceId = createInstance();
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "ResNoXp");
        SessionLease session = sessions.openSession(playerId, "paper-a", instanceId, Duration.ofSeconds(30));
        ResourceSourceSnapshot source = sources.ensureSource(instanceId, "no-xp.01", SOURCE);

        gathering.harvestAndFulfill(
                UUID.randomUUID(),
                session.sessionId(),
                "paper-a",
                session.stateVersion(),
                source.sourceId(),
                "resource.harvest"
        );

        assertTrue(verifier.verify(100).isEmpty());
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
}
