package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CraftingExperienceFulfillmentRepositoryIntegrationTest {
    private static final SkillId CRAFTING = new SkillId("crafting");
    private static final String RECIPE = "starter.sword";
    private static final int RECIPE_VERSION = 1;
    private static final long XP = 25L;

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CraftingExperienceFulfillmentRepository fulfillments;

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

        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(curve(CRAFTING)));
        CraftingExperienceCatalog experience = new CraftingExperienceCatalog(
                List.of(new CraftingExperienceDefinition(RECIPE, RECIPE_VERSION, CRAFTING, XP)),
                skills
        );
        fulfillments = new CraftingExperienceFulfillmentRepository(dataSource, experience, skills);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        craft_experience_fulfillments,
                        craft_records,
                        skill_xp_awards,
                        processed_operations,
                        player_skills,
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
    void fulfillsOneCraftExactlyOnceAndDrainsRecoveryQueue() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "CrafterXp");
        UUID craftId = insertCraft(playerId, RECIPE, RECIPE_VERSION);

        assertEquals(List.of(craftId), fulfillments.listUnfulfilled(10));
        CraftingExperienceFulfillmentResult first = fulfillments.fulfill(craftId);
        CraftingExperienceFulfillmentResult retry = fulfillments.fulfill(craftId);

        assertEquals(first, retry);
        assertEquals(CraftingExperienceFulfillmentRepository.xpOperationId(craftId), first.xpOperationId());
        assertEquals(playerId, first.experienceAward().playerId());
        assertEquals(CRAFTING, first.experienceAward().skillId());
        assertEquals(XP, first.experienceAward().requestedExperience());
        assertEquals(XP, first.experienceAward().grantedExperience());
        assertEquals(XP, skillExperience(playerId));
        assertEquals(1L, rowCount("craft_experience_fulfillments"));
        assertEquals(1L, skillAwardCount(first.xpOperationId()));
        assertTrue(fulfillments.listUnfulfilled(10).isEmpty());
    }

    @Test
    void concurrentFulfillmentConvergesOnOneXpOperation() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "CrafterRace");
        UUID craftId = insertCraft(playerId, RECIPE, RECIPE_VERSION);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CraftingExperienceFulfillmentResult> first = executor.submit(() -> fulfillments.fulfill(craftId));
            Future<CraftingExperienceFulfillmentResult> second = executor.submit(() -> fulfillments.fulfill(craftId));
            assertEquals(first.get(), second.get());
        }

        assertEquals(XP, skillExperience(playerId));
        assertEquals(1L, rowCount("craft_experience_fulfillments"));
        assertEquals(1L, skillAwardCount(CraftingExperienceFulfillmentRepository.xpOperationId(craftId)));
    }

    @Test
    void unknownRecipePolicyRemainsUnfulfilledAndFailsClosed() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "CrafterUnknown");
        UUID craftId = insertCraft(playerId, "unknown.recipe", 7);

        assertThrows(CraftingException.class, () -> fulfillments.listUnfulfilled(10));
        assertThrows(CraftingException.class, () -> fulfillments.fulfill(craftId));
        assertEquals(0L, skillExperience(playerId));
        assertEquals(0L, rowCount("craft_experience_fulfillments"));
    }

    @Test
    void fulfillmentEvidenceIsAppendOnly() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "CrafterAudit");
        UUID craftId = insertCraft(playerId, RECIPE, RECIPE_VERSION);
        fulfillments.fulfill(craftId);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM craft_experience_fulfillments
                     WHERE craft_id = ?
                     """)) {
            statement.setObject(1, craftId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private UUID insertCraft(UUID playerId, String recipeId, int recipeVersion) throws SQLException {
        UUID craftId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO craft_records(
                         craft_id, operation_id, player_id, recipe_id, recipe_version, result_data
                     ) VALUES (?, ?, ?, ?, ?, '{}'::jsonb)
                     """)) {
            statement.setObject(1, craftId);
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, playerId);
            statement.setString(4, recipeId);
            statement.setInt(5, recipeVersion);
            statement.executeUpdate();
        }
        return craftId;
    }

    private long skillExperience(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT experience
                     FROM player_skills
                     WHERE player_id = ? AND skill_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, CRAFTING.value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0L;
            }
        }
    }

    private long skillAwardCount(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM skill_xp_awards
                     WHERE operation_id = ?
                     """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long rowCount(String table) throws SQLException {
        if (!table.equals("craft_experience_fulfillments")) {
            throw new IllegalArgumentException("unsupported test table: " + table);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
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
