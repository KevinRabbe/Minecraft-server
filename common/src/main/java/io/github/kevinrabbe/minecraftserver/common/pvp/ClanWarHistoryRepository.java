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

/** Bounded completed-war history for the isolated 1.8.9 Clan Wars category. */
public final class ClanWarHistoryRepository {
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;

    public ClanWarHistoryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<ClanWarHistoryEntry> recent(int limit) throws SQLException {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT w.war_id,
                            w.challenger_clan_id,
                            challenger.name AS challenger_name,
                            challenger.tag AS challenger_tag,
                            w.defender_clan_id,
                            defender.name AS defender_name,
                            defender.tag AS defender_tag,
                            r.winning_clan_id,
                            r.losing_clan_id,
                            r.challenger_rating_before,
                            r.challenger_rating_after,
                            r.defender_rating_before,
                            r.defender_rating_after,
                            r.ruleset_id,
                            r.ruleset_version,
                            r.rating_policy_version,
                            r.rating_k_factor,
                            w.team_size,
                            w.started_at,
                            w.finished_at
                     FROM clan_war_results r
                     JOIN clan_wars w ON w.war_id = r.war_id
                     JOIN clans challenger ON challenger.clan_id = w.challenger_clan_id
                     JOIN clans defender ON defender.clan_id = w.defender_clan_id
                     WHERE w.status = 'COMPLETED'
                     ORDER BY w.finished_at DESC, w.war_id DESC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<ClanWarHistoryEntry> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new ClanWarHistoryEntry(
                            rows.getObject("war_id", UUID.class),
                            rows.getObject("challenger_clan_id", UUID.class),
                            rows.getString("challenger_name"),
                            rows.getString("challenger_tag"),
                            rows.getObject("defender_clan_id", UUID.class),
                            rows.getString("defender_name"),
                            rows.getString("defender_tag"),
                            rows.getObject("winning_clan_id", UUID.class),
                            rows.getObject("losing_clan_id", UUID.class),
                            rows.getInt("challenger_rating_before"),
                            rows.getInt("challenger_rating_after"),
                            rows.getInt("defender_rating_before"),
                            rows.getInt("defender_rating_after"),
                            rows.getString("ruleset_id"),
                            rows.getInt("ruleset_version"),
                            rows.getInt("rating_policy_version"),
                            rows.getInt("rating_k_factor"),
                            rows.getInt("team_size"),
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
