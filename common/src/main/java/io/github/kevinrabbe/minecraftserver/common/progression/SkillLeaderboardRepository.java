package io.github.kevinrabbe.minecraftserver.common.progression;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded read projection for MMO skill rankings; mutation remains owned by {@link SkillProgressionRepository}. */
public final class SkillLeaderboardRepository {
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;
    private final SkillProgressionCatalog catalog;

    public SkillLeaderboardRepository(DataSource dataSource, SkillProgressionCatalog catalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Returns players with persisted XP for one skill, highest XP first.
     * Equal-XP rows are ordered by stable internal player ID so mutable names never decide rank order.
     */
    public List<SkillLeaderboardEntry> top(SkillId skillId, int limit) throws SQLException {
        SkillProgressionDefinition definition = catalog.require(Objects.requireNonNull(skillId, "skillId"));
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT ps.player_id,
                            ps.experience,
                            progression_state.active_skill_cap,
                            (
                                SELECT pn.name
                                FROM player_names pn
                                WHERE pn.player_id = ps.player_id
                                ORDER BY pn.last_seen_at DESC, pn.name ASC
                                LIMIT 1
                            ) AS player_name
                     FROM player_skills ps
                     CROSS JOIN progression_state
                     WHERE progression_state.singleton = TRUE
                       AND ps.skill_id = ?
                     ORDER BY ps.experience DESC, ps.player_id ASC
                     LIMIT ?
                     """)) {
            statement.setString(1, skillId.value());
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<SkillLeaderboardEntry> result = new ArrayList<>();
                int rank = 1;
                while (rows.next()) {
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    String playerName = rows.getString("player_name");
                    if (playerName == null || playerName.isBlank()) {
                        throw new SkillProgressionException(
                                "Leaderboard player has no current name projection: " + playerId
                        );
                    }
                    long experience = rows.getLong("experience");
                    int activeCap = rows.getInt("active_skill_cap");
                    result.add(new SkillLeaderboardEntry(
                            rank++,
                            playerId,
                            playerName,
                            skillId,
                            experience,
                            definition.levelForExperience(experience, activeCap),
                            activeCap
                    ));
                }
                return List.copyOf(result);
            }
        }
    }
}
