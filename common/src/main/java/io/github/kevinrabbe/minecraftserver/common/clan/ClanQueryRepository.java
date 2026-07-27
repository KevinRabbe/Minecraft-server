package io.github.kevinrabbe.minecraftserver.common.clan;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded read projections for clan roster and pending invitations; clan mutations remain elsewhere. */
public final class ClanQueryRepository {
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;

    public ClanQueryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Package-local persistence context for other clan repositories; never exposes the DataSource to adapters. */
    DataSource dataSource() {
        return dataSource;
    }

    public List<ClanMemberView> listMembers(UUID clanId, int limit) throws SQLException {
        Objects.requireNonNull(clanId, "clanId");
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT cm.player_id,
                            cm.role,
                            cm.joined_at,
                            (
                                SELECT pn.name
                                FROM player_names pn
                                WHERE pn.player_id = cm.player_id
                                ORDER BY pn.last_seen_at DESC, pn.name ASC
                                LIMIT 1
                            ) AS player_name
                     FROM clan_members cm
                     WHERE cm.clan_id = ?
                     ORDER BY CASE cm.role
                                  WHEN 'LEADER' THEN 0
                                  WHEN 'OFFICER' THEN 1
                                  ELSE 2
                              END,
                              cm.joined_at ASC,
                              cm.player_id ASC
                     LIMIT ?
                     """)) {
            statement.setObject(1, clanId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<ClanMemberView> result = new ArrayList<>();
                while (rows.next()) {
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    result.add(new ClanMemberView(
                            playerId,
                            requireName(rows.getString("player_name"), playerId),
                            ClanRole.valueOf(rows.getString("role")),
                            rows.getTimestamp("joined_at").toInstant()
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    /** Resolves only the latest known Minecraft name among current members of the exact clan. */
    public Optional<ClanMemberView> findMemberByCurrentName(UUID clanId, String playerName) throws SQLException {
        Objects.requireNonNull(clanId, "clanId");
        String normalizedName = requirePlayerName(playerName);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT cm.player_id,
                            cm.role,
                            cm.joined_at,
                            current_name.name AS player_name
                     FROM clan_members cm
                     JOIN LATERAL (
                         SELECT pn.name
                         FROM player_names pn
                         WHERE pn.player_id = cm.player_id
                         ORDER BY pn.last_seen_at DESC, pn.name ASC
                         LIMIT 1
                     ) current_name ON TRUE
                     WHERE cm.clan_id = ?
                       AND LOWER(current_name.name) = LOWER(?)
                     ORDER BY cm.player_id ASC
                     LIMIT 2
                     """)) {
            statement.setObject(1, clanId);
            statement.setString(2, normalizedName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                UUID playerId = rows.getObject("player_id", UUID.class);
                ClanMemberView result = new ClanMemberView(
                        playerId,
                        requireName(rows.getString("player_name"), playerId),
                        ClanRole.valueOf(rows.getString("role")),
                        rows.getTimestamp("joined_at").toInstant()
                );
                if (rows.next()) {
                    throw new ClanMembershipException(
                            "Current clan member name is ambiguous: " + normalizedName
                    );
                }
                return Optional.of(result);
            }
        }
    }

    public List<ClanInvitationView> listPendingInvitations(UUID clanId, int limit) throws SQLException {
        Objects.requireNonNull(clanId, "clanId");
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT ci.invite_id,
                            ci.invited_player_id,
                            ci.invited_by_player_id,
                            ci.created_at,
                            ci.expires_at,
                            (
                                SELECT pn.name
                                FROM player_names pn
                                WHERE pn.player_id = ci.invited_player_id
                                ORDER BY pn.last_seen_at DESC, pn.name ASC
                                LIMIT 1
                            ) AS invited_name,
                            (
                                SELECT pn.name
                                FROM player_names pn
                                WHERE pn.player_id = ci.invited_by_player_id
                                ORDER BY pn.last_seen_at DESC, pn.name ASC
                                LIMIT 1
                            ) AS inviter_name
                     FROM clan_invitations ci
                     WHERE ci.clan_id = ?
                       AND ci.status = 'PENDING'
                       AND ci.expires_at > NOW()
                     ORDER BY ci.created_at ASC, ci.invite_id ASC
                     LIMIT ?
                     """)) {
            statement.setObject(1, clanId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<ClanInvitationView> result = new ArrayList<>();
                while (rows.next()) {
                    UUID invitedPlayerId = rows.getObject("invited_player_id", UUID.class);
                    UUID invitedByPlayerId = rows.getObject("invited_by_player_id", UUID.class);
                    result.add(new ClanInvitationView(
                            rows.getObject("invite_id", UUID.class),
                            invitedPlayerId,
                            requireName(rows.getString("invited_name"), invitedPlayerId),
                            invitedByPlayerId,
                            requireName(rows.getString("inviter_name"), invitedByPlayerId),
                            rows.getTimestamp("created_at").toInstant(),
                            rows.getTimestamp("expires_at").toInstant()
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static int requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private static String requirePlayerName(String name) {
        name = Objects.requireNonNull(name, "playerName").trim();
        if (name.isEmpty() || name.length() > 16) {
            throw new IllegalArgumentException("player name must contain 1-16 characters");
        }
        return name;
    }

    private static String requireName(String name, UUID playerId) {
        if (name == null || name.isBlank()) {
            throw new ClanMembershipException("Clan query player has no current name projection: " + playerId);
        }
        return name;
    }
}
