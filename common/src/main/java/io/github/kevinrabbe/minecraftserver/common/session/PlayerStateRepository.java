package io.github.kevinrabbe.minecraftserver.common.session;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.UUID;

/** Commits persistent player snapshots only when the caller still owns the live session/version. */
public final class PlayerStateRepository {
    private final DataSource dataSource;

    public PlayerStateRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public PlayerStateSnapshot load(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT state_version, logical_zone_id, entry_point, state_payload
                     FROM player_state
                     WHERE player_id = ?
                     """)) {
            statement.setObject(1, playerId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Player state does not exist: " + playerId);
                }
                return new PlayerStateSnapshot(
                        playerId,
                        results.getLong("state_version"),
                        results.getString("logical_zone_id"),
                        results.getString("entry_point"),
                        results.getBytes("state_payload")
                );
            }
        }
    }

    public long commit(
            UUID sessionId,
            String backendId,
            long expectedStateVersion,
            String logicalZoneId,
            String entryPoint,
            byte[] statePayload
    ) throws SQLException {
        Objects.requireNonNull(sessionId, "sessionId");
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        if (expectedStateVersion < 0) {
            throw new IllegalArgumentException("expectedStateVersion must not be negative");
        }

        String normalizedZoneId = normalizeOptional(logicalZoneId);
        String normalizedEntryPoint = normalizeOptional(entryPoint);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SessionOwner owner = lockSessionOwner(connection, sessionId);
                if (!owner.leaseValid()
                        || !Objects.equals(owner.backendId(), normalizedBackendId)
                        || owner.stateVersion() != expectedStateVersion
                        || (owner.status() != SessionStatus.ACTIVE
                        && owner.status() != SessionStatus.TRANSFERRING
                        && owner.status() != SessionStatus.RECOVERING)) {
                    throw new SessionConflictException("Stale or non-owning player state commit rejected for session " + sessionId);
                }

                long newVersion;
                try (PreparedStatement updateState = connection.prepareStatement("""
                        UPDATE player_state
                        SET state_version = state_version + 1,
                            logical_zone_id = ?,
                            entry_point = ?,
                            state_payload = ?,
                            updated_at = NOW()
                        WHERE player_id = ?
                          AND state_version = ?
                        RETURNING state_version
                        """)) {
                    setNullableText(updateState, 1, normalizedZoneId);
                    setNullableText(updateState, 2, normalizedEntryPoint);
                    if (statePayload == null) {
                        updateState.setNull(3, Types.BINARY);
                    } else {
                        updateState.setBytes(3, statePayload);
                    }
                    updateState.setObject(4, owner.playerId());
                    updateState.setLong(5, expectedStateVersion);

                    try (ResultSet results = updateState.executeQuery()) {
                        if (!results.next()) {
                            throw new SessionConflictException("Player state version changed concurrently");
                        }
                        newVersion = results.getLong("state_version");
                    }
                }

                try (PreparedStatement updateSession = connection.prepareStatement("""
                        UPDATE player_sessions
                        SET state_version = ?
                        WHERE network_session_id = ?
                          AND state_version = ?
                        """)) {
                    updateSession.setLong(1, newVersion);
                    updateSession.setObject(2, sessionId);
                    updateSession.setLong(3, expectedStateVersion);
                    if (updateSession.executeUpdate() != 1) {
                        throw new SessionConflictException("Session version changed concurrently");
                    }
                }

                connection.commit();
                return newVersion;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static SessionOwner lockSessionOwner(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id,
                       owner_backend_id,
                       state_version,
                       status,
                       lease_expires_at IS NOT NULL AND lease_expires_at > NOW() AS lease_valid
                FROM player_sessions
                WHERE network_session_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, sessionId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SessionConflictException("Unknown session: " + sessionId);
                }
                return new SessionOwner(
                        results.getObject("player_id", UUID.class),
                        results.getString("owner_backend_id"),
                        results.getLong("state_version"),
                        SessionStatus.valueOf(results.getString("status")),
                        results.getBoolean("lease_valid")
                );
            }
        }
    }

    private static void setNullableText(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record SessionOwner(
            UUID playerId,
            String backendId,
            long stateVersion,
            SessionStatus status,
            boolean leaseValid
    ) {
    }
}
