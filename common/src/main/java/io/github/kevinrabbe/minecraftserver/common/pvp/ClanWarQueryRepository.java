package io.github.kevinrabbe.minecraftserver.common.pvp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded player-facing Clan-War reads. No custody, item, economy, or settlement authority is exposed here. */
public final class ClanWarQueryRepository {
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;

    public ClanWarQueryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Optional<UUID> findClanIdByTag(String tag) throws SQLException {
        String normalizedTag = requireTag(tag);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT clan_id
                     FROM clans
                     WHERE tag = ?
                     LIMIT 2
                     """)) {
            statement.setString(1, normalizedTag);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                UUID clanId = rows.getObject("clan_id", UUID.class);
                if (rows.next()) {
                    throw new ClanWarException("clan tag resolved to multiple clans: " + normalizedTag);
                }
                return Optional.of(clanId);
            }
        }
    }

    public Optional<ClanWarView> load(UUID warId) throws SQLException {
        Objects.requireNonNull(warId, "warId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(baseSelect() + " WHERE war.war_id = ?")) {
            statement.setObject(1, warId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                ClanWarView result = read(rows);
                if (rows.next()) throw new ClanWarException("duplicate Clan War projection: " + warId);
                return Optional.of(result);
            }
        }
    }

    public List<ClanWarView> listOpenForClan(UUID clanId, int limit) throws SQLException {
        Objects.requireNonNull(clanId, "clanId");
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(baseSelect() + """
                     WHERE (war.challenger_clan_id = ? OR war.defender_clan_id = ?)
                       AND war.status IN ('CHALLENGED', 'ACCEPTED', 'ROSTER_LOCKED', 'ACTIVE')
                     ORDER BY war.created_at ASC, war.war_id ASC
                     LIMIT ?
                     """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, clanId);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<ClanWarView> result = new ArrayList<>();
                while (rows.next()) result.add(read(rows));
                return List.copyOf(result);
            }
        }
    }

    private static String baseSelect() {
        return """
                SELECT war.war_id,
                       war.challenger_clan_id,
                       challenger.name AS challenger_name,
                       challenger.tag AS challenger_tag,
                       war.defender_clan_id,
                       defender.name AS defender_name,
                       defender.tag AS defender_tag,
                       war.status,
                       war.winning_clan_id,
                       war.team_size,
                       war.ruleset_id,
                       war.ruleset_version,
                       war.created_at,
                       war.started_at,
                       war.finished_at,
                       (
                           SELECT COUNT(*)
                           FROM clan_war_rosters roster
                           WHERE roster.war_id = war.war_id
                             AND roster.clan_id = war.challenger_clan_id
                             AND roster.released_at IS NULL
                       ) AS challenger_roster_count,
                       (
                           SELECT COUNT(*)
                           FROM clan_war_rosters roster
                           WHERE roster.war_id = war.war_id
                             AND roster.clan_id = war.defender_clan_id
                             AND roster.released_at IS NULL
                       ) AS defender_roster_count
                FROM clan_wars war
                JOIN clans challenger ON challenger.clan_id = war.challenger_clan_id
                JOIN clans defender ON defender.clan_id = war.defender_clan_id
                """;
    }

    private static ClanWarView read(ResultSet row) throws SQLException {
        Timestamp startedAt = row.getTimestamp("started_at");
        Timestamp finishedAt = row.getTimestamp("finished_at");
        return new ClanWarView(
                row.getObject("war_id", UUID.class),
                row.getObject("challenger_clan_id", UUID.class),
                row.getString("challenger_name"),
                row.getString("challenger_tag"),
                row.getObject("defender_clan_id", UUID.class),
                row.getString("defender_name"),
                row.getString("defender_tag"),
                ClanWarStatus.valueOf(row.getString("status")),
                row.getObject("winning_clan_id", UUID.class),
                row.getInt("team_size"),
                row.getInt("challenger_roster_count"),
                row.getInt("defender_roster_count"),
                row.getString("ruleset_id"),
                row.getInt("ruleset_version"),
                row.getTimestamp("created_at").toInstant(),
                startedAt == null ? null : startedAt.toInstant(),
                finishedAt == null ? null : finishedAt.toInstant()
        );
    }

    private static int requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private static String requireTag(String tag) {
        tag = Objects.requireNonNull(tag, "tag").trim().toUpperCase(Locale.ROOT);
        if (tag.isEmpty() || tag.length() > 16) {
            throw new IllegalArgumentException("clan tag must contain 1-16 characters");
        }
        return tag;
    }
}
