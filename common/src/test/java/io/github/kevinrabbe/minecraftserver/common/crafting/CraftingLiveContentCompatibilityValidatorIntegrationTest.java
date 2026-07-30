package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalogLoader;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CraftingLiveContentCompatibilityValidatorIntegrationTest {
    private static final String RECIPE_ID = "starter.sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CraftingContentCatalog v1;
    private CraftingContentCatalog v2Only;

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

        ItemCatalog items = new ItemCatalogLoader().loadResource("/content/items.json");
        SkillProgressionCatalog skills = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");
        v1 = new CraftingContentCatalogLoader().loadResource("/content/crafting.json", items, skills);

        CraftRecipeVersion oldRecipe = v1.recipes().require(RECIPE_ID, 1);
        CraftingExperienceDefinition oldExperience = v1.experience().require(RECIPE_ID, 1);
        CraftRecipeCatalog recipesV2 = new CraftRecipeCatalog(List.of(new CraftRecipeVersion(
                2,
                oldRecipe.recipe(),
                oldRecipe.outputRollProfile()
        )), items);
        CraftingExperienceCatalog experienceV2 = new CraftingExperienceCatalog(List.of(
                new CraftingExperienceDefinition(
                        RECIPE_ID,
                        2,
                        oldExperience.skillId(),
                        oldExperience.requestedExperience()
                )
        ), skills);
        v2Only = new CraftingContentCatalog(recipesV2, experienceV2);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        craft_experience_fulfillments,
                        craft_records,
                        crafting_commission_returns,
                        crafting_commission_materials,
                        crafting_commissions,
                        pending_commodity_deliveries,
                        economic_ledger,
                        processed_operations,
                        player_skills,
                        player_sessions,
                        player_state,
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
    void liveCommissionAndPendingCraftXpKeepHistoricalVersionLoadedOnlyWhileNeeded() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "CraftCompat");
        UUID commissionId = insertOpenCommission(playerId);

        assertDoesNotThrow(() -> CraftingLiveContentCompatibilityValidator.validate(dataSource, v1));
        assertThrows(
                CraftingException.class,
                () -> CraftingLiveContentCompatibilityValidator.validate(dataSource, v2Only)
        );

        cancelCommission(commissionId);
        assertDoesNotThrow(() -> CraftingLiveContentCompatibilityValidator.validate(dataSource, v2Only));

        UUID craftId = insertUnfulfilledCraft(playerId);
        assertThrows(
                CraftingException.class,
                () -> CraftingLiveContentCompatibilityValidator.validate(dataSource, v2Only)
        );

        markExperienceFulfilled(craftId);
        assertDoesNotThrow(() -> CraftingLiveContentCompatibilityValidator.validate(dataSource, v2Only));
    }

    private UUID insertOpenCommission(UUID playerId) throws SQLException {
        UUID commissionId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO crafting_commissions(
                         commission_id, requester_player_id, recipe_id, recipe_version,
                         status, payment_minor, state_version, create_operation_id
                     ) VALUES (?, ?, ?, 1, 'OPEN', 0, 0, ?)
                     """)) {
            statement.setObject(1, commissionId);
            statement.setObject(2, playerId);
            statement.setString(3, RECIPE_ID);
            statement.setObject(4, UUID.randomUUID());
            statement.executeUpdate();
        }
        return commissionId;
    }

    private void cancelCommission(UUID commissionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE crafting_commissions
                     SET status = 'CANCELLED', cancel_operation_id = ?, settled_at = NOW(), state_version = state_version + 1
                     WHERE commission_id = ?
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, commissionId);
            if (statement.executeUpdate() != 1) throw new AssertionError("expected one commission to cancel");
        }
    }

    private UUID insertUnfulfilledCraft(UUID playerId) throws SQLException {
        UUID craftId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO craft_records(
                         craft_id, operation_id, player_id, recipe_id, recipe_version, result_data
                     ) VALUES (?, ?, ?, ?, 1, '{}'::jsonb)
                     """)) {
            statement.setObject(1, craftId);
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, playerId);
            statement.setString(4, RECIPE_ID);
            statement.executeUpdate();
        }
        return craftId;
    }

    private void markExperienceFulfilled(UUID craftId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO craft_experience_fulfillments(craft_id, xp_operation_id)
                     VALUES (?, ?)
                     """)) {
            statement.setObject(1, craftId);
            statement.setObject(2, CraftingExperienceFulfillmentRepository.xpOperationId(craftId));
            statement.executeUpdate();
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
