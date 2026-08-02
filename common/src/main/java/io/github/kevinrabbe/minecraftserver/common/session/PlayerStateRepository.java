package io.github.kevinrabbe.minecraftserver.common.session;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;

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
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long newVersion = commitWithinTransaction(
                        connection,
                        sessionId,
                        backendId,
                        expectedStateVersion,
                        logicalZoneId,
                        entryPoint,
                        statePayload
                );
                connection.commit();
                return newVersion;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Applies the fenced player-state commit inside a caller-owned transaction without extra payload validation. */
    public long commitWithinTransaction(
            Connection connection,
            UUID sessionId,
            String backendId,
            long expectedStateVersion,
            String logicalZoneId,
            String entryPoint,
            byte[] statePayload
    ) throws SQLException {
        return commitWithinTransaction(
                connection,
                sessionId,
                backendId,
                expectedStateVersion,
                logicalZoneId,
                entryPoint,
                statePayload,
                null
        );
    }

    /**
     * Applies a fenced player-state commit and validates a sensitive payload transition while the session and current
     * state are transactionally locked.
     */
    public long commitWithinTransaction(
            Connection connection,
            UUID sessionId,
            String backendId,
            long expectedStateVersion,
            String logicalZoneId,
            String entryPoint,
            byte[] statePayload,
            PlayerStateMutationValidator validator
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(sessionId, "sessionId");
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        UUID backendIncarnationId = BackendRegistry.requireProcessIncarnation(normalizedBackendId);
        if (expectedStateVersion < 0) {
            throw new IllegalArgumentException("expectedStateVersion must not be negative");
        }
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException("commitWithinTransaction requires autoCommit=false");
        }

        String normalizedZoneId = normalizeOptional(logicalZoneId);
        String normalizedEntryPoint = normalizeOptional(entryPoint);

        SessionOwner owner = lockSessionOwner(connection, sessionId);
        if (!owner.leaseValid()
                || !Objects.equals(owner.backendId(), normalizedBackendId)
                || !Objects.equals(owner.backendIncarnationId(), backendIncarnationId)
                || owner.stateVersion() != expectedStateVersion
                || (owner.status() != SessionStatus.ACTIVE
                && owner.status() != SessionStatus.RECOVERING)) {
            throw new SessionConflictException(
                    "Stale, replaced, frozen, or non-owning player state commit rejected for session " + sessionId
            );
        }

        if (validator != null) {
            byte[] currentPayload = lockCurrentStatePayload(connection, owner.playerId(), expectedStateVersion);
            validator.validate(owner.playerId(), copy(currentPayload), copy(statePayload));
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
                  AND owner_backend_incarnation_id = ?
                  AND state_version = ?
                """)) {
            updateSession.setLong(1, newVersion);
            updateSession.setObject(2, sessionId);
            updateSession.setObject(3, backendIncarnationId);
            updateSession.setLong(4, expectedStateVersion);
            if (updateSession.executeUpdate() != 1) {
                throw new SessionConflictException("Session ownership or version changed concurrently");
            }
        }

        return newVersion;
    }

    private static byte[] lockCurrentStatePayload(Connection connection, UUID playerId, long expectedStateVersion)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state_payload
                FROM player_state
                WHERE player_id = ?
                  AND state_version = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, playerId);
            statement.setLong(2, expectedStateVersion);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SessionConflictException("Player state version changed before sensitive mutation validation");
                }
                return result.getBytes("state_payload");
            }
        }
    }

    private static SessionOwner lockSessionOwner(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id,
                       owner_backend_id,
                       owner_backend_incarnation_id,
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
                        results.getObject("owner_backend_incarnation_id", UUID.class),
                        results.getLong("state_version"),
                        SessionStatus.valueOf(results.getString("status")),
                        results.getBoolean("lease_valid")
                );
            }
        }
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
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
            UUID backendIncarnationId,
            long stateVersion,
            SessionStatus status,
            boolean leaseValid
    ) {
    }
}
