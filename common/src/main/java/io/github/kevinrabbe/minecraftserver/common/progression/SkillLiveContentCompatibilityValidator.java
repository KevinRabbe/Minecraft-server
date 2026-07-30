package io.github.kevinrabbe.minecraftserver.common.progression;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Startup compatibility gate for durable skill progression and XP evidence. */
public final class SkillLiveContentCompatibilityValidator {
    private SkillLiveContentCompatibilityValidator() { }

    public static void validate(DataSource dataSource, SkillProgressionCatalog catalog) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(catalog, "catalog");

        try (Connection connection = dataSource.getConnection()) {
            int activeCap = readActiveCap(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH referenced_skill_state AS (
                        SELECT skill_id, experience
                        FROM player_skills
                        UNION ALL
                        SELECT skill_id, new_experience AS experience
                        FROM skill_xp_awards
                    )
                    SELECT skill_id, MAX(experience) AS maximum_experience
                    FROM referenced_skill_state
                    GROUP BY skill_id
                    ORDER BY skill_id
                    """);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    SkillId skillId = new SkillId(rows.getString("skill_id"));
                    SkillProgressionDefinition definition = catalog.require(skillId);
                    long maximumExperience = rows.getLong("maximum_experience");
                    long activeCapCeiling = definition.experienceForLevel(activeCap);
                    if (maximumExperience > activeCapCeiling) {
                        throw new SkillProgressionException(
                                "Loaded skill curve cannot represent durable XP for " + skillId.value()
                                        + " at active cap " + activeCap
                                        + ": maximumExperience=" + maximumExperience
                                        + ", activeCapCeiling=" + activeCapCeiling
                        );
                    }
                }
            }
        }
    }

    private static int readActiveCap(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT active_skill_cap
                FROM progression_state
                WHERE singleton = TRUE
                """);
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new SkillProgressionException("Global progression_state row is missing");
            }
            int activeCap = row.getInt("active_skill_cap");
            if (row.next()) {
                throw new SkillProgressionException("Multiple global progression_state rows exist");
            }
            return activeCap;
        }
    }
}
