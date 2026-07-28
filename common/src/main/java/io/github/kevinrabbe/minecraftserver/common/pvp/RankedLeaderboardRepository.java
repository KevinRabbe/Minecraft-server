package io.github.kevinrabbe.minecraftserver.common.pvp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded read model for the separate 1.8.9 Ranked leaderboard.
 *
 * <p>Rows are derived from immutable match results plus the current authoritative rating. Zero-match rating rows are
 * omitted. If result history ever contains a different ruleset/rating-policy identity, this V1 read model fails closed
 * so a future ladder migration must decide explicitly how histories are separated.</p>
 */
public final class RankedLeaderboardRepository {
    private final DataSource dataSource;
    private final RankedArenaRuleset ruleset;

    public RankedLeaderboardRepository(DataSource dataSource, RankedArenaRuleset ruleset) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.ruleset = Objects.requireNonNull(ruleset, "ruleset");
    }

    public List<RankedLeaderboardEntry> top(int limit) throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }

        try (Connection connection = dataSource.getConnection()) {
            requireCompatibleHistory(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH participant_results AS (
                        SELECT match.player_a_id AS player_id,
                               result.winner_player_id,
                               result.player_a_rating_before AS rating_before,
                               result.player_a_rating_after AS rating_after,
                               result.created_at
                        FROM ranked_match_results result
                        JOIN ranked_matches match ON match.match_id = result.match_id
                        WHERE result.ruleset_id = ?
                          AND result.ruleset_version = ?
                          AND result.rating_policy_version = ?
                          AND result.rating_k_factor = ?
                        UNION ALL
                        SELECT match.player_b_id AS player_id,
                               result.winner_player_id,
                               result.player_b_rating_before AS rating_before,
                               result.player_b_rating_after AS rating_after,
                               result.created_at
                        FROM ranked_match_results result
                        JOIN ranked_matches match ON match.match_id = result.match_id
                        WHERE result.ruleset_id = ?
                          AND result.ruleset_version = ?
                          AND result.rating_policy_version = ?
                          AND result.rating_k_factor = ?
                    ),
                    stats AS (
                        SELECT rating.player_id,
                               rating.rating,
                               GREATEST(
                                   rating.rating,
                                   MAX(GREATEST(history.rating_before, history.rating_after))
                               ) AS peak_rating,
                               COUNT(*) FILTER (WHERE history.winner_player_id = rating.player_id) AS wins,
                               COUNT(*) FILTER (WHERE history.winner_player_id <> rating.player_id) AS losses,
                               MAX(history.created_at) AS last_result_at
                        FROM ranked_ratings rating
                        JOIN participant_results history ON history.player_id = rating.player_id
                        GROUP BY rating.player_id, rating.rating
                    ),
                    current_names AS (
                        SELECT DISTINCT ON (player_id)
                               player_id,
                               name
                        FROM player_names
                        ORDER BY player_id, last_seen_at DESC, name ASC
                    )
                    SELECT stats.player_id,
                           current_names.name,
                           stats.rating,
                           stats.peak_rating,
                           stats.wins,
                           stats.losses,
                           stats.last_result_at
                    FROM stats
                    JOIN current_names ON current_names.player_id = stats.player_id
                    ORDER BY stats.rating DESC, stats.player_id ASC
                    LIMIT ?
                    """)) {
                bindRuleset(statement, 1);
                bindRuleset(statement, 5);
                statement.setInt(9, limit);

                try (ResultSet rows = statement.executeQuery()) {
                    List<RankedLeaderboardEntry> result = new ArrayList<>();
                    int position = 1;
                    while (rows.next()) {
                        result.add(new RankedLeaderboardEntry(
                                position++,
                                rows.getObject("player_id", java.util.UUID.class),
                                rows.getString("name"),
                                rows.getInt("rating"),
                                rows.getInt("peak_rating"),
                                rows.getLong("wins"),
                                rows.getLong("losses"),
                                rows.getTimestamp("last_result_at").toInstant(),
                                ruleset.rulesetId(),
                                ruleset.rulesetVersion(),
                                ruleset.ratingPolicyVersion()
                        ));
                    }
                    return List.copyOf(result);
                }
            }
        }
    }

    private void requireCompatibleHistory(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM ranked_match_results
                WHERE ruleset_id <> ?
                   OR ruleset_version <> ?
                   OR rating_policy_version <> ?
                   OR rating_k_factor <> ?
                LIMIT 1
                """)) {
            statement.setString(1, ruleset.rulesetId());
            statement.setInt(2, ruleset.rulesetVersion());
            statement.setInt(3, ruleset.ratingPolicyVersion());
            statement.setInt(4, ruleset.kFactor());
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    throw new RankedArenaException(
                            "Ranked leaderboard history contains another ruleset/rating policy; explicit ladder migration required"
                    );
                }
            }
        }
    }

    private void bindRuleset(PreparedStatement statement, int startIndex) throws SQLException {
        statement.setString(startIndex, ruleset.rulesetId());
        statement.setInt(startIndex + 1, ruleset.rulesetVersion());
        statement.setInt(startIndex + 2, ruleset.ratingPolicyVersion());
        statement.setInt(startIndex + 3, ruleset.kFactor());
    }
}
