package io.github.kevinrabbe.minecraftserver.common.pvp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded read-only ladder for the isolated 1.8.9 Ranked Arena category. */
public final class RankedArenaLeaderboardRepository {
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;

    public RankedArenaLeaderboardRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<RankedArenaLeaderboardEntry> top(int limit) throws SQLException {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT rr.player_id,
                            rr.rating,
                            (
                                SELECT pn.name
                                FROM player_names pn
                                WHERE pn.player_id = rr.player_id
                                ORDER BY pn.last_seen_at DESC, pn.name ASC
                                LIMIT 1
                            ) AS player_name,
                            (
                                SELECT COUNT(*)
                                FROM ranked_match_results r
                                WHERE r.winner_player_id = rr.player_id
                            ) AS wins,
                            (
                                SELECT COUNT(*)
                                FROM ranked_match_results r
                                WHERE r.loser_player_id = rr.player_id
                            ) AS losses
                     FROM ranked_ratings rr
                     ORDER BY rr.rating DESC, rr.player_id ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<RankedArenaLeaderboardEntry> result = new ArrayList<>();
                int rank = 1;
                while (rows.next()) {
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    String playerName = rows.getString("player_name");
                    if (playerName == null || playerName.isBlank()) {
                        throw new RankedArenaException("Ranked leaderboard player has no current name: " + playerId);
                    }
                    result.add(new RankedArenaLeaderboardEntry(
                            rank++,
                            playerId,
                            playerName,
                            rows.getInt("rating"),
                            rows.getLong("wins"),
                            rows.getLong("losses")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
