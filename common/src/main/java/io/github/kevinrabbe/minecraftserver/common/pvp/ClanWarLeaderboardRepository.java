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

/** Bounded read-only ladder for the isolated 1.8.9 Clan Wars category. */
public final class ClanWarLeaderboardRepository {
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;

    public ClanWarLeaderboardRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<ClanWarLeaderboardEntry> top(int limit) throws SQLException {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT r.clan_id,
                            c.name AS clan_name,
                            c.tag AS clan_tag,
                            r.rating,
                            (
                                SELECT COUNT(*)
                                FROM clan_war_results result
                                WHERE result.winning_clan_id = r.clan_id
                            ) AS wins,
                            (
                                SELECT COUNT(*)
                                FROM clan_war_results result
                                WHERE result.losing_clan_id = r.clan_id
                            ) AS losses
                     FROM clan_war_ratings r
                     JOIN clans c ON c.clan_id = r.clan_id
                     ORDER BY r.rating DESC, r.clan_id ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<ClanWarLeaderboardEntry> result = new ArrayList<>();
                int rank = 1;
                while (rows.next()) {
                    result.add(new ClanWarLeaderboardEntry(
                            rank++,
                            rows.getObject("clan_id", UUID.class),
                            rows.getString("clan_name"),
                            rows.getString("clan_tag"),
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
