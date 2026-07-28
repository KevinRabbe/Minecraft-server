package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ItemUseEligibilityServiceIntegrationTest {
    private static final SkillId COMBAT = new SkillId("combat");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private SkillProgressionRepository skills;
    private ItemUseEligibilityService eligibility;

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
        SkillProgressionCatalog skillCatalog = new SkillProgressionCatalog(List.of(skill(COMBAT)));
        skills = new SkillProgressionRepository(dataSource, skillCatalog);

        ItemCatalog itemCatalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        "equipment.unrestricted",
                        "WOODEN_SWORD",
                        "Unrestricted Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                ),
                new ItemDefinition(
                        "equipment.combat_5",
                        "IRON_SWORD",
                        "Combat 5 Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL,
                        ItemRollProfile.NONE,
                        new ItemUseRequirements(List.of(
                                new ItemSkillRequirement(COMBAT, 5)
                        ))
                )
        ));
        eligibility = new ItemUseEligibilityService(
                itemCatalog,
                new SkillProgressionQueryRepository(dataSource, skillCatalog)
        );
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
    void unrestrictedDefinitionNeedsNoProgressionReadToAllowUse() throws Exception {
        ItemUseEligibility result = eligibility.evaluate(UUID.randomUUID(), "equipment.unrestricted");

        assertTrue(result.allowed());
        assertTrue(result.currentSkillLevels().isEmpty());
        assertTrue(result.unmetRequirements().isEmpty());
    }

    @Test
    void restrictedDefinitionReportsExactUnmetRequirement() throws Exception {
        UUID playerId = player("UseReqLow");
        skills.awardExperience(UUID.randomUUID(), playerId, COMBAT, 40, "test.use_req");

        ItemUseEligibility result = eligibility.evaluate(playerId, "equipment.combat_5");

        assertFalse(result.allowed());
        assertEquals(Map.of(COMBAT, 4), result.currentSkillLevels());
        assertEquals(List.of(new ItemSkillRequirement(COMBAT, 5)), result.unmetRequirements());
    }

    @Test
    void reachingRequirementAllowsUseWithoutChangingOwnership() throws Exception {
        UUID playerId = player("UseReqReady");
        skills.awardExperience(UUID.randomUUID(), playerId, COMBAT, 50, "test.use_req");

        ItemUseEligibility result = eligibility.evaluate(playerId, "equipment.combat_5");

        assertTrue(result.allowed());
        assertEquals(Map.of(COMBAT, 5), result.currentSkillLevels());
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
