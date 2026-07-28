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

/** Read-only bounded projection of ACCEPTED Clan Wars whose exact rosters are complete and ready for trusted locking. */
public final class ClanWarPreparationRepository {
    private static final int MAX_LIMIT = 500;

    private final DataSource dataSource;

    public ClanWarPreparationRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<UUID> listRosterLockReady(int limit) throws SQLException {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT war.war_id
                     FROM clan_wars war
                     WHERE war.status = 'ACCEPTED'
                       AND (
                           SELECT COUNT(*)
                           FROM clan_war_rosters roster
                           WHERE roster.war_id = war.war_id
                             AND roster.clan_id = war.challenger_clan_id
                             AND roster.released_at IS NULL
                       ) = war.team_size
                       AND (
                           SELECT COUNT(*)
                           FROM clan_war_rosters roster
                           WHERE roster.war_id = war.war_id
                             AND roster.clan_id = war.defender_clan_id
                             AND roster.released_at IS NULL
                       ) = war.team_size
                     ORDER BY war.created_at ASC, war.war_id ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<UUID> result = new ArrayList<>();
                while (rows.next()) result.add(rows.getObject("war_id", UUID.class));
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
