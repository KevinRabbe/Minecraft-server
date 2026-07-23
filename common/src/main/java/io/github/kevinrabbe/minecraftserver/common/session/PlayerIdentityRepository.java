package io.github.kevinrabbe.minecraftserver.common.session;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Resolves Minecraft UUIDs to stable internal player IDs without using names as identity. */
public final class PlayerIdentityRepository {
    private final DataSource dataSource;

    public PlayerIdentityRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public UUID ensurePlayer(UUID minecraftUuid, String currentName) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        String normalizedName = requirePlayerName(currentName);
        UUID candidatePlayerId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insertPlayer = connection.prepareStatement("""
                        INSERT INTO players (player_id, minecraft_uuid)
                        VALUES (?, ?)
                        ON CONFLICT (minecraft_uuid) DO NOTHING
                        """)) {
                    insertPlayer.setObject(1, candidatePlayerId);
                    insertPlayer.setObject(2, minecraftUuid);
                    insertPlayer.executeUpdate();
                }

                UUID playerId;
                try (PreparedStatement selectPlayer = connection.prepareStatement("""
                        SELECT player_id
                        FROM players
                        WHERE minecraft_uuid = ?
                        """)) {
                    selectPlayer.setObject(1, minecraftUuid);
                    try (ResultSet results = selectPlayer.executeQuery()) {
                        if (!results.next()) {
                            throw new SQLException("Player row disappeared after identity upsert: " + minecraftUuid);
                        }
                        playerId = results.getObject("player_id", UUID.class);
                    }
                }

                try (PreparedStatement ensureState = connection.prepareStatement("""
                        INSERT INTO player_state (player_id)
                        VALUES (?)
                        ON CONFLICT (player_id) DO NOTHING
                        """)) {
                    ensureState.setObject(1, playerId);
                    ensureState.executeUpdate();
                }

                try (PreparedStatement upsertName = connection.prepareStatement("""
                        INSERT INTO player_names (player_id, name)
                        VALUES (?, ?)
                        ON CONFLICT (player_id, name) DO UPDATE SET
                            last_seen_at = NOW()
                        """)) {
                    upsertName.setObject(1, playerId);
                    upsertName.setString(2, normalizedName);
                    upsertName.executeUpdate();
                }

                connection.commit();
                return playerId;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static String requirePlayerName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("currentName must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 16) {
            throw new IllegalArgumentException("currentName must not exceed 16 characters");
        }
        return normalized;
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
