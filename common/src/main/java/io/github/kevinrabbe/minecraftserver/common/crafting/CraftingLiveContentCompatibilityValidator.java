package io.github.kevinrabbe.minecraftserver.common.crafting;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Startup compatibility gate for durable crafting obligations that still need versioned content.
 *
 * <p>OPEN/ACCEPTED commissions must retain both their exact recipe and XP policy so they can still be accepted,
 * completed and awarded exactly as promised. Immutable craft records no longer need their recipe after output issuance,
 * but an unfulfilled craft still needs its exact XP policy until craft_experience_fulfillments records completion.</p>
 */
public final class CraftingLiveContentCompatibilityValidator {
    private CraftingLiveContentCompatibilityValidator() { }

    public static void validate(DataSource dataSource, CraftingContentCatalog content) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(content, "content");

        validateLiveCommissions(dataSource, content);
        validateUnfulfilledCraftExperience(dataSource, content.experience());
    }

    private static void validateLiveCommissions(
            DataSource dataSource,
            CraftingContentCatalog content
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT recipe_id, recipe_version
                     FROM crafting_commissions
                     WHERE status IN ('OPEN', 'ACCEPTED')
                     ORDER BY recipe_id, recipe_version
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String recipeId = rows.getString("recipe_id");
                int recipeVersion = rows.getInt("recipe_version");
                content.recipes().require(recipeId, recipeVersion);
                content.experience().require(recipeId, recipeVersion);
            }
        }
    }

    private static void validateUnfulfilledCraftExperience(
            DataSource dataSource,
            CraftingExperienceCatalog experience
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT c.recipe_id, c.recipe_version
                     FROM craft_records c
                     LEFT JOIN craft_experience_fulfillments f ON f.craft_id = c.craft_id
                     WHERE f.craft_id IS NULL
                     ORDER BY c.recipe_id, c.recipe_version
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                experience.require(rows.getString("recipe_id"), rows.getInt("recipe_version"));
            }
        }
    }
}
