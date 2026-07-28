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

/** Bounded completed-match history for the isolated 1.8.9 Ranked Arena category. */
public final class RankedArenaHistoryRepository {
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;

    public RankedArenaHistoryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<RankedArenaHistoryEntry> recent(int limit) throws SQLException {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT m.match_id,
                            m.player_a_id,
                            (
                                SELECT pn.name
                                FROM player_names pn
                                WHERE pn.player_id = m.player_a_id
                                ORDER BY pn.last_seen_at DESC, pn.name ASC
                                LIMIT 1
                            ) AS player_a_name,
                            m.player_b_id,
                            (
                                SELECT pn.name
                                FROM player_names pn
                                WHERE pn.player_id = m.player_b_id
                                ORDER BY pn.last_seen_at DESC, pn.name ASC
                                LIMIT 1
                            ) AS player_b_name,
                            r.winner_player_id,
                            r.loser_player_id,
                            r.player_a_rating_before,
                            r.player_a_rating_after,
                            r.player_b_rating_before,
                            r.player_b_rating_after,
                            r.ruleset_id,
                            r.ruleset_version,
                            r.rating_policy_version,
                            r.rating_k_factor,
                            m.started_at,
                            m.finished_at
                     FROM ranked_match_results r
                     JOIN ranked_matches m ON m.match_id = r.match_id
                     WHERE m.status = 'COMPLETED'
                     ORDER BY m.finished_at DESC, m.match_id DESC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<RankedArenaHistoryEntry> result = new ArrayList<>();
                while (rows.next()) {
                    UUID matchId = rows.getObject("match_id", UUID.class);
                    String playerAName = rows.getString("player_a_name");
                    String playerBName = rows.getString("player_b_name");
                    if (playerAName == null || playerAName.isBlank() || playerBName == null || playerBName.isBlank()) {
                        throw new RankedArenaException("Ranked history participant has no current name projection: " + matchId);
                    }
                    result.add(new RankedArenaHistoryEntry(
                            matchId,
                            rows.getObject("player_a_id", UUID.class),
                            playerAName,
                            rows.getObject("player_b_id", UUID.class),
                            playerBName,
                            rows.getObject("winner_player_id", UUID.class),
                            rows.getObject("loser_player_id", UUID.class),
                            rows.getInt("player_a_rating_before"),
                            rows.getInt("player_a_rating_after"),
                            rows.getInt("player_b_rating_before"),
                            rows.getInt("player_b_rating_after"),
                            rows.getString("ruleset_id"),
                            rows.getInt("ruleset_version"),
                            rows.getInt("rating_policy_version"),
                            rows.getInt("rating_k_factor"),
                            rows.getTimestamp("started_at").toInstant(),
                            rows.getTimestamp("finished_at").toInstant()
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
