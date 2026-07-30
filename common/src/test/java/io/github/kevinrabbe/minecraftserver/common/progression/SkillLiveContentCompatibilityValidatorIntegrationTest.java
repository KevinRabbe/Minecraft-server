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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SkillLiveContentCompatibilityValidatorIntegrationTest {
    private static final SkillId MINING = new SkillId("mining");
    private static final SkillId WOODCUTTING = new SkillId("woodcutting");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;

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
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE players RESTART IDENTITY CASCADE");
            statement.execute("TRUNCATE TABLE skill_xp_awards, processed_operations RESTART IDENTITY CASCADE");
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
    void stableSkillIdMayBeRetunedButCannotDisappearOrShrinkBelowDurableXp() throws Exception {
        SkillProgressionCatalog original = catalog(curve(MINING, 100));
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "SkillCompat");
        new SkillProgressionRepository(dataSource, original).awardExperience(
                UUID.randomUUID(),
                playerId,
                MINING,
                1_000,
                "test.skill_compat"
        );

        SkillProgressionCatalog retuned = catalog(curve(MINING, 200));
        SkillProgressionCatalog missing = catalog(curve(WOODCUTTING, 100));
        SkillProgressionCatalog tooSmall = catalog(curve(MINING, 10));

        assertDoesNotThrow(() -> SkillLiveContentCompatibilityValidator.validate(dataSource, original));
        assertDoesNotThrow(() -> SkillLiveContentCompatibilityValidator.validate(dataSource, retuned));
        assertThrows(
                SkillProgressionException.class,
                () -> SkillLiveContentCompatibilityValidator.validate(dataSource, missing)
        );
        assertThrows(
                SkillProgressionException.class,
                () -> SkillLiveContentCompatibilityValidator.validate(dataSource, tooSmall)
        );
    }

    private static SkillProgressionCatalog catalog(SkillProgressionDefinition definition) {
        return new SkillProgressionCatalog(List.of(definition));
    }

    private static SkillProgressionDefinition curve(SkillId skillId, long experiencePerLevel) {
        ArrayList<Long> cumulative = new ArrayList<>(101);
        for (int level = 0; level <= 100; level++) {
            cumulative.add(Math.multiplyExact((long) level, experiencePerLevel));
        }
        return new SkillProgressionDefinition(skillId, cumulative);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
