package io.github.kevinrabbe.minecraftserver.common.session;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-backed fencing for exactly one persistent player-state writer at a time. */
public final class PlayerSessionRepository {
    private final DataSource dataSource;

    public PlayerSessionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public SessionLease openSession(
            UUID playerId,
            String backendId,
            UUID instanceId,
            Duration leaseDuration
    ) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        long leaseMillis = requirePositiveDurationMillis(leaseDuration, "leaseDuration");
        UUID sessionId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockPlayer(connection, playerId);
                retireExpiredLiveSessionOrReject(connection, playerId);
                long stateVersion = currentStateVersion(connection, playerId);

                String sql = """
                        INSERT INTO player_sessions (
                            network_session_id,
                            player_id,
                            owner_backend_id,
                            owner_instance_id,
                            state_version,
                            status,
                            lease_expires_at,
                            last_heartbeat_at
                        ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW() + (? * INTERVAL '1 millisecond'), NOW())
                        RETURNING lease_expires_at
                        """;

                Instant leaseExpiresAt;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setObject(1, sessionId);
                    statement.setObject(2, playerId);
                    statement.setString(3, normalizedBackendId);
                    statement.setObject(4, instanceId);
                    statement.setLong(5, stateVersion);
                    statement.setLong(6, leaseMillis);
                    try (ResultSet results = statement.executeQuery()) {
                        results.next();
                        leaseExpiresAt = results.getTimestamp("lease_expires_at").toInstant();
                    }
                }

                connection.commit();
                return new SessionLease(
                        sessionId,
                        playerId,
                        normalizedBackendId,
                        instanceId,
                        stateVersion,
                        SessionStatus.ACTIVE,
                        leaseExpiresAt
                );
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public SessionLease heartbeat(
            UUID sessionId,
            String backendId,
            Duration leaseDuration
    ) throws SQLException {
        Objects.requireNonNull(sessionId, "sessionId");
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        long leaseMillis = requirePositiveDurationMillis(leaseDuration, "leaseDuration");

        String sql = """
                UPDATE player_sessions
                SET last_heartbeat_at = NOW(),
                    lease_expires_at = NOW() + (? * INTERVAL '1 millisecond')
                WHERE network_session_id = ?
                  AND owner_backend_id = ?
                  AND status IN ('ACTIVE', 'TRANSFERRING', 'RECOVERING')
                  AND lease_expires_at > NOW()
                RETURNING player_id, owner_instance_id, state_version, status, lease_expires_at
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, leaseMillis);
            statement.setObject(2, sessionId);
            statement.setString(3, normalizedBackendId);

            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SessionConflictException("Session lease is not owned by backend or has expired: " + sessionId);
                }
                return readLease(results, sessionId, normalizedBackendId);
            }
        }
    }

    public TransferTicket beginTransfer(
            UUID sessionId,
            String sourceBackendId,
            String targetZoneId,
            long expectedStateVersion,
            Duration ticketLifetime
    ) throws SQLException {
        Objects.requireNonNull(sessionId, "sessionId");
        String normalizedSourceBackendId = requireNonBlank(sourceBackendId, "sourceBackendId");
        String normalizedTargetZoneId = requireNonBlank(targetZoneId, "targetZoneId");
        if (expectedStateVersion < 0) {
            throw new IllegalArgumentException("expectedStateVersion must not be negative");
        }
        long ticketMillis = requirePositiveDurationMillis(ticketLifetime, "ticketLifetime");
        UUID transferId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SessionRow session = lockSession(connection, sessionId);
                requireOwnedActiveSession(session, normalizedSourceBackendId, expectedStateVersion);

                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE player_sessions
                        SET status = 'TRANSFERRING'
                        WHERE network_session_id = ?
                        """)) {
                    update.setObject(1, sessionId);
                    update.executeUpdate();
                }

                Instant expiresAt;
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO transfer_tickets (
                            transfer_id,
                            network_session_id,
                            player_id,
                            source_backend_id,
                            target_zone_id,
                            expected_state_version,
                            expires_at
                        ) VALUES (?, ?, ?, ?, ?, ?, NOW() + (? * INTERVAL '1 millisecond'))
                        RETURNING expires_at
                        """)) {
                    insert.setObject(1, transferId);
                    insert.setObject(2, sessionId);
                    insert.setObject(3, session.playerId());
                    insert.setString(4, normalizedSourceBackendId);
                    insert.setString(5, normalizedTargetZoneId);
                    insert.setLong(6, expectedStateVersion);
                    insert.setLong(7, ticketMillis);
                    try (ResultSet results = insert.executeQuery()) {
                        results.next();
                        expiresAt = results.getTimestamp("expires_at").toInstant();
                    }
                }

                connection.commit();
                return new TransferTicket(
                        transferId,
                        sessionId,
                        session.playerId(),
                        normalizedSourceBackendId,
                        normalizedTargetZoneId,
                        expectedStateVersion,
                        expiresAt
                );
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public SessionLease claimTransfer(
            UUID transferId,
            String targetBackendId,
            UUID targetInstanceId,
            Duration leaseDuration
    ) throws SQLException {
        Objects.requireNonNull(transferId, "transferId");
        String normalizedTargetBackendId = requireNonBlank(targetBackendId, "targetBackendId");
        long leaseMillis = requirePositiveDurationMillis(leaseDuration, "leaseDuration");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                TransferRow transfer = lockTransfer(connection, transferId);
                if (transfer.consumed() || !transfer.unexpired()) {
                    throw new SessionConflictException("Transfer ticket is consumed or expired: " + transferId);
                }

                SessionRow session = lockSession(connection, transfer.sessionId());
                if (session.status() != SessionStatus.TRANSFERRING
                        || !Objects.equals(session.ownerBackendId(), transfer.sourceBackendId())
                        || session.stateVersion() != transfer.expectedStateVersion()) {
                    throw new SessionConflictException("Transfer ticket no longer matches authoritative session state: " + transferId);
                }

                Instant leaseExpiresAt;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE player_sessions
                        SET owner_backend_id = ?,
                            owner_instance_id = ?,
                            status = 'ACTIVE',
                            lease_expires_at = NOW() + (? * INTERVAL '1 millisecond'),
                            last_heartbeat_at = NOW()
                        WHERE network_session_id = ?
                        RETURNING lease_expires_at
                        """)) {
                    update.setString(1, normalizedTargetBackendId);
                    update.setObject(2, targetInstanceId);
                    update.setLong(3, leaseMillis);
                    update.setObject(4, transfer.sessionId());
                    try (ResultSet results = update.executeQuery()) {
                        results.next();
                        leaseExpiresAt = results.getTimestamp("lease_expires_at").toInstant();
                    }
                }

                try (PreparedStatement consume = connection.prepareStatement("""
                        UPDATE transfer_tickets
                        SET consumed_at = NOW()
                        WHERE transfer_id = ? AND consumed_at IS NULL
                        """)) {
                    consume.setObject(1, transferId);
                    if (consume.executeUpdate() != 1) {
                        throw new SessionConflictException("Transfer ticket was concurrently consumed: " + transferId);
                    }
                }

                connection.commit();
                return new SessionLease(
                        transfer.sessionId(),
                        transfer.playerId(),
                        normalizedTargetBackendId,
                        targetInstanceId,
                        transfer.expectedStateVersion(),
                        SessionStatus.ACTIVE,
                        leaseExpiresAt
                );
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public void disconnect(UUID sessionId, String backendId) throws SQLException {
        Objects.requireNonNull(sessionId, "sessionId");
        String normalizedBackendId = requireNonBlank(backendId, "backendId");

        String sql = """
                UPDATE player_sessions
                SET owner_backend_id = NULL,
                    owner_instance_id = NULL,
                    status = 'DISCONNECTED',
                    lease_expires_at = NULL,
                    last_heartbeat_at = NOW(),
                    disconnected_at = NOW()
                WHERE network_session_id = ?
                  AND owner_backend_id = ?
                  AND status IN ('ACTIVE', 'TRANSFERRING', 'RECOVERING')
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sessionId);
            statement.setString(2, normalizedBackendId);
            if (statement.executeUpdate() != 1) {
                throw new SessionConflictException("Session is not owned by backend: " + sessionId);
            }
        }
    }

    private static void lockPlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id
                FROM players
                WHERE player_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Unknown player: " + playerId);
                }
            }
        }
    }

    private static void retireExpiredLiveSessionOrReject(Connection connection, UUID playerId) throws SQLException {
        String sql = """
                SELECT network_session_id,
                       lease_expires_at IS NOT NULL AND lease_expires_at > NOW() AS lease_valid
                FROM player_sessions
                WHERE player_id = ?
                  AND status IN ('ACTIVE', 'TRANSFERRING', 'RECOVERING')
                FOR UPDATE
                """;

        UUID expiredSessionId = null;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    UUID existingSessionId = results.getObject("network_session_id", UUID.class);
                    if (results.getBoolean("lease_valid")) {
                        throw new SessionConflictException("Player already has a valid live session: " + existingSessionId);
                    }
                    expiredSessionId = existingSessionId;
                }
            }
        }

        if (expiredSessionId != null) {
            try (PreparedStatement retire = connection.prepareStatement("""
                    UPDATE player_sessions
                    SET owner_backend_id = NULL,
                        owner_instance_id = NULL,
                        status = 'DISCONNECTED',
                        lease_expires_at = NULL,
                        disconnected_at = NOW()
                    WHERE network_session_id = ?
                    """)) {
                retire.setObject(1, expiredSessionId);
                retire.executeUpdate();
            }
        }
    }

    private static long currentStateVersion(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state_version
                FROM player_state
                WHERE player_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Player state does not exist: " + playerId);
                }
                return results.getLong("state_version");
            }
        }
    }

    private static SessionRow lockSession(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id,
                       owner_backend_id,
                       owner_instance_id,
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
                return new SessionRow(
                        results.getObject("player_id", UUID.class),
                        results.getString("owner_backend_id"),
                        results.getObject("owner_instance_id", UUID.class),
                        results.getLong("state_version"),
                        SessionStatus.valueOf(results.getString("status")),
                        results.getBoolean("lease_valid")
                );
            }
        }
    }

    private static TransferRow lockTransfer(Connection connection, UUID transferId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT network_session_id,
                       player_id,
                       source_backend_id,
                       expected_state_version,
                       consumed_at IS NOT NULL AS consumed,
                       expires_at > NOW() AS unexpired
                FROM transfer_tickets
                WHERE transfer_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, transferId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SessionConflictException("Unknown transfer ticket: " + transferId);
                }
                return new TransferRow(
                        results.getObject("network_session_id", UUID.class),
                        results.getObject("player_id", UUID.class),
                        results.getString("source_backend_id"),
                        results.getLong("expected_state_version"),
                        results.getBoolean("consumed"),
                        results.getBoolean("unexpired")
                );
            }
        }
    }

    private static void requireOwnedActiveSession(SessionRow session, String backendId, long expectedStateVersion) {
        if (!session.leaseValid()
                || session.status() != SessionStatus.ACTIVE
                || !Objects.equals(session.ownerBackendId(), backendId)
                || session.stateVersion() != expectedStateVersion) {
            throw new SessionConflictException("Session ownership/version no longer matches transfer request");
        }
    }

    private static SessionLease readLease(ResultSet results, UUID sessionId, String backendId) throws SQLException {
        UUID instanceId = results.getObject("owner_instance_id", UUID.class);
        Timestamp expiresAt = results.getTimestamp("lease_expires_at");
        return new SessionLease(
                sessionId,
                results.getObject("player_id", UUID.class),
                backendId,
                instanceId,
                results.getLong("state_version"),
                SessionStatus.valueOf(results.getString("status")),
                expiresAt.toInstant()
        );
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static long requirePositiveDurationMillis(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        long millis = duration.toMillis();
        if (millis < 1) {
            throw new IllegalArgumentException(name + " must be at least 1 millisecond");
        }
        return millis;
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record SessionRow(
            UUID playerId,
            String ownerBackendId,
            UUID ownerInstanceId,
            long stateVersion,
            SessionStatus status,
            boolean leaseValid
    ) {
    }

    private record TransferRow(
            UUID sessionId,
            UUID playerId,
            String sourceBackendId,
            long expectedStateVersion,
            boolean consumed,
            boolean unexpired
    ) {
    }
}
