package io.github.kevinrabbe.minecraftserver.common.progression;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SkillProgressionQueryRepositoryIntegrationTest {
    private static final SkillId COMBAT = new SkillId("combat");
    private static final SkillId MINING = new SkillId("mining");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private SkillProgressionCatalog catalog;
    private SkillProgressionRepository authority;
    private SkillProgressionQueryRepository query;

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
        catalog = new SkillProgressionCatalog(List.of(skill(COMBAT), skill(MINING)));
        authority = new SkillProgressionRepository(dataSource, catalog);
        query = new SkillProgressionQueryRepository(dataSource, catalog);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        skill_xp_awards,
                        player_skills,
                        processed_operations,
                        player_state,
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
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
    void missingSkillRowsProjectAsLevelZeroInOneBatch() throws Exception {
        UUID playerId = player("SkillQueryZero");

        Map<SkillId, SkillProgressSnapshot> result = query.load(playerId, List.of(MINING, COMBAT));

        assertEquals(2, result.size());
        assertEquals(0, result.get(COMBAT).level());
        assertEquals(0L, result.get(COMBAT).experience());
        assertEquals(0L, result.get(COMBAT).stateVersion());
        assertEquals(50, result.get(COMBAT).activeCap());
        assertEquals(0, result.get(MINING).level());
    }

    @Test
    void batchProjectionReflectsAuthoritativeXpAndDeduplicatesRequestedSkills() throws Exception {
        UUID playerId = player("SkillQueryXp");
        authority.awardExperience(UUID.randomUUID(), playerId, COMBAT, 50, "test.query_xp");
        authority.awardExperience(UUID.randomUUID(), playerId, MINING, 30, "test.query_xp");

        Map<SkillId, SkillProgressSnapshot> result = query.load(
                playerId,
                List.of(COMBAT, MINING, COMBAT)
        );

        assertEquals(2, result.size());
        assertEquals(5, result.get(COMBAT).level());
        assertEquals(50L, result.get(COMBAT).experience());
        assertEquals(1L, result.get(COMBAT).stateVersion());
        assertEquals(3, result.get(MINING).level());
        assertEquals(Map.of(COMBAT, 5, MINING, 3), query.loadLevels(playerId, List.of(COMBAT, MINING)));
    }

    @Test
    void unknownSkillFailsBeforeReturningAProjection() throws Exception {
        UUID playerId = player("SkillQueryBad");

        assertThrows(
                SkillProgressionException.class,
                () -> query.load(playerId, List.of(new SkillId("unknown.skill")))
        );
    }

    @Test
    void unknownPlayerFailsClosedForNonEmptyRequirementSet() {
        assertThrows(
                SkillProgressionException.class,
                () -> query.load(UUID.randomUUID(), List.of(COMBAT))
        );
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private static SkillProgressionDefinition skill(SkillId skillId) {
        ArrayList<Long> thresholds = new ArrayList<>(101);
        for (int level = 0; level <= 100; level++) {
            thresholds.add(level * 10L);
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
