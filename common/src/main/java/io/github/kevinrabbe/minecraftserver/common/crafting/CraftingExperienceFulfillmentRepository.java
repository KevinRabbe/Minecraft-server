package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionRepository;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillXpAwardResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Recoverable exactly-once XP fulfillment for immutable craft records.
 *
 * <p>The XP operation ID is derived from craft_id. A crash after the XP commit but before the fulfillment marker is
 * therefore safe: retrying the same craft calls the idempotent skill authority with the same operation ID and then
 * records completion.</p>
 */
public final class CraftingExperienceFulfillmentRepository {
    private static final String XP_REASON = "craft.experience";
    private static final int MAX_RECOVERY_BATCH = 1_000;

    private final DataSource dataSource;
    private final CraftingExperienceCatalog experienceCatalog;
    private final SkillProgressionRepository skills;

    public CraftingExperienceFulfillmentRepository(
            DataSource dataSource,
            CraftingExperienceCatalog experienceCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.experienceCatalog = Objects.requireNonNull(experienceCatalog, "experienceCatalog");
        this.skills = new SkillProgressionRepository(
                dataSource,
                Objects.requireNonNull(skillCatalog, "skillCatalog")
        );
    }

    public CraftingExperienceFulfillmentResult fulfill(UUID craftId) throws SQLException {
        Objects.requireNonNull(craftId, "craftId");
        CraftRow craft = loadCraft(craftId);
        CraftingExperienceDefinition policy = experienceCatalog.require(craft.recipeId(), craft.recipeVersion());
        UUID xpOperationId = xpOperationId(craftId);

        SkillXpAwardResult award = skills.awardExperience(
                xpOperationId,
                craft.playerId(),
                policy.skillId(),
                policy.requestedExperience(),
                XP_REASON
        );

        Instant completedAt = recordFulfillment(craftId, xpOperationId);
        return new CraftingExperienceFulfillmentResult(
                craftId,
                xpOperationId,
                award,
                completedAt
        );
    }

    public List<UUID> listUnfulfilled(int limit) throws SQLException {
        if (limit < 1 || limit > MAX_RECOVERY_BATCH) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_RECOVERY_BATCH);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT c.craft_id, c.recipe_id, c.recipe_version
                     FROM craft_records c
                     LEFT JOIN craft_experience_fulfillments f ON f.craft_id = c.craft_id
                     WHERE f.craft_id IS NULL
                     ORDER BY c.created_at, c.craft_id
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet row = statement.executeQuery()) {
                ArrayList<UUID> result = new ArrayList<>();
                while (row.next()) {
                    String recipeId = row.getString("recipe_id");
                    int recipeVersion = row.getInt("recipe_version");
                    experienceCatalog.require(recipeId, recipeVersion);
                    result.add(row.getObject("craft_id", UUID.class));
                }
                return List.copyOf(result);
            }
        }
    }

    public static UUID xpOperationId(UUID craftId) {
        Objects.requireNonNull(craftId, "craftId");
        return UUID.nameUUIDFromBytes(
                ("craft-experience:" + craftId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private CraftRow loadCraft(UUID craftId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id, recipe_id, recipe_version
                     FROM craft_records
                     WHERE craft_id = ?
                     """)) {
            statement.setObject(1, craftId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new CraftingException("Unknown craft record: " + craftId);
                }
                CraftRow result = new CraftRow(
                        row.getObject("player_id", UUID.class),
                        row.getString("recipe_id"),
                        row.getInt("recipe_version")
                );
                if (row.next()) {
                    throw new CraftingException("craft_id resolved to multiple records: " + craftId);
                }
                return result;
            }
        }
    }

    private Instant recordFulfillment(UUID craftId, UUID xpOperationId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO craft_experience_fulfillments(craft_id, xp_operation_id)
                        VALUES (?, ?)
                        ON CONFLICT (craft_id) DO NOTHING
                        RETURNING completed_at
                        """)) {
                    insert.setObject(1, craftId);
                    insert.setObject(2, xpOperationId);
                    try (ResultSet row = insert.executeQuery()) {
                        if (row.next()) {
                            Instant completedAt = row.getTimestamp("completed_at").toInstant();
                            connection.commit();
                            return completedAt;
                        }
                    }
                }

                try (PreparedStatement existing = connection.prepareStatement("""
                        SELECT xp_operation_id, completed_at
                        FROM craft_experience_fulfillments
                        WHERE craft_id = ?
                        FOR SHARE
                        """)) {
                    existing.setObject(1, craftId);
                    try (ResultSet row = existing.executeQuery()) {
                        if (!row.next()) {
                            throw new CraftingException(
                                    "craft XP fulfillment disappeared after concurrent insert: " + craftId
                            );
                        }
                        UUID existingOperationId = row.getObject("xp_operation_id", UUID.class);
                        if (!xpOperationId.equals(existingOperationId)) {
                            throw new CraftingException(
                                    "craft XP fulfillment is bound to a different operation: " + craftId
                            );
                        }
                        Instant completedAt = row.getTimestamp("completed_at").toInstant();
                        connection.commit();
                        return completedAt;
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record CraftRow(UUID playerId, String recipeId, int recipeVersion) {
        private CraftRow {
            playerId = Objects.requireNonNull(playerId, "playerId");
            if (recipeId == null || recipeId.isBlank() || recipeVersion < 0) {
                throw new CraftingException("craft record contains invalid recipe identity");
            }
            recipeId = recipeId.trim();
        }
    }
}
