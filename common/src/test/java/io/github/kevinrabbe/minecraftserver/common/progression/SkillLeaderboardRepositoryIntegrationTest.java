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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SkillLeaderboardRepositoryIntegrationTest {
    private static final SkillId MINING = new SkillId("mining");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private SkillProgressionRepository progression;
    private SkillLeaderboardRepository leaderboards;

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
        SkillProgressionCatalog catalog = new SkillProgressionCatalog(List.of(curve(MINING)));
        progression = new SkillProgressionRepository(dataSource, catalog);
        leaderboards = new SkillLeaderboardRepository(dataSource, catalog);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        skill_xp_awards,
                        processed_operations,
                        player_skills,
                        player_names,
                        player_state,
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
    void topRanksAuthoritativeExperienceWithCurrentNameAndCapDerivedLevel() throws Exception {
        UUID lowMinecraft = UUID.randomUUID();
        UUID low = identities.ensurePlayer(lowMinecraft, "MinerLow");
        UUID highA = identities.ensurePlayer(UUID.randomUUID(), "MinerHighA");
        UUID highB = identities.ensurePlayer(UUID.randomUUID(), "MinerHighB");

        progression.awardExperience(UUID.randomUUID(), low, MINING, 500, "test.leaderboard");
        progression.awardExperience(UUID.randomUUID(), highA, MINING, 800, "test.leaderboard");
        progression.awardExperience(UUID.randomUUID(), highB, MINING, 800, "test.leaderboard");
        identities.ensurePlayer(lowMinecraft, "MinerRenamed");

        List<SkillLeaderboardEntry> first = leaderboards.top(MINING, 10);
        List<SkillLeaderboardEntry> replay = leaderboards.top(MINING, 10);

        assertEquals(first, replay);
        assertEquals(3, first.size());
        assertEquals(800L, first.get(0).experience());
        assertEquals(800L, first.get(1).experience());
        assertEquals(500L, first.get(2).experience());
        assertEquals(8, first.get(0).level());
        assertEquals(50, first.get(0).activeCap());
        assertEquals("MinerRenamed", first.get(2).playerName());
        assertEquals(List.of(1, 2, 3), first.stream().map(SkillLeaderboardEntry::rank).toList());
        assertTrue(first.subList(0, 2).stream().map(SkillLeaderboardEntry::playerId).toList().containsAll(List.of(highA, highB)));
    }

    @Test
    void resultLimitIsBounded() throws Exception {
        for (int index = 0; index < 3; index++) {
            UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "Miner" + index);
            progression.awardExperience(
                    UUID.randomUUID(),
                    playerId,
                    MINING,
                    100L * (index + 1),
                    "test.leaderboard"
            );
        }

        assertEquals(2, leaderboards.top(MINING, 2).size());
        assertThrows(IllegalArgumentException.class, () -> leaderboards.top(MINING, 0));
        assertThrows(IllegalArgumentException.class, () -> leaderboards.top(MINING, 101));
        assertThrows(
                SkillProgressionException.class,
                () -> leaderboards.top(new SkillId("unknown"), 1)
        );
    }

    private static SkillProgressionDefinition curve(SkillId skillId) {
        ArrayList<Long> thresholds = new ArrayList<>();
        for (int level = 0; level <= SkillProgressionDefinition.LONG_TERM_MAX_LEVEL; level++) {
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
}
