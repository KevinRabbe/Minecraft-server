package io.github.kevinrabbe.minecraftserver.common.session;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Restores a source-owned attached session to ACTIVE after its transfer ticket expires unclaimed. */
public final class TransferRecoveryRepository {
    private final DataSource dataSource;

    public TransferRecoveryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Set<UUID> recoverExpiredAttachedTransfers(
            String backendId,
            Collection<UUID> attachedSessionIds
    ) throws SQLException {
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        Set<UUID> requested = normalizedSessionIds(attachedSessionIds);
        if (requested.isEmpty()) {
            return Set.of();
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            Array requestedArray = connection.createArrayOf("uuid", requested.toArray());
            try {
                Set<UUID> recoverable = closeExpiredTickets(
                        connection,
                        normalizedBackendId,
                        requestedArray
                );
                if (recoverable.isEmpty()) {
                    connection.commit();
                    return Set.of();
                }

                Array recoverableArray = connection.createArrayOf("uuid", recoverable.toArray());
                try {
                    Set<UUID> recovered = reactivateSessions(
                            connection,
                            normalizedBackendId,
                            recoverableArray
                    );
                    if (!recovered.equals(recoverable)) {
                        throw new SessionConflictException("Expired transfer recovery changed concurrently");
                    }
                    connection.commit();
                    return Set.copyOf(recovered);
                } finally {
                    recoverableArray.free();
                }
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            } finally {
                requestedArray.free();
            }
        }
    }

    private static Set<UUID> closeExpiredTickets(
            Connection connection,
            String backendId,
            Array requestedSessionIds
    ) throws SQLException {
        String sql = """
                UPDATE transfer_tickets tt
                SET consumed_at = NOW()
                FROM player_sessions ps
                WHERE tt.network_session_id = ps.network_session_id
                  AND tt.consumed_at IS NULL
                  AND tt.expires_at <= NOW()
                  AND ps.network_session_id = ANY (?)
                  AND ps.owner_backend_id = ?
                  AND ps.status = 'TRANSFERRING'
                  AND ps.lease_expires_at > NOW()
                  AND ps.state_version = tt.expected_state_version
                  AND ps.owner_backend_id = tt.source_backend_id
                RETURNING tt.network_session_id
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, requestedSessionIds);
            statement.setString(2, backendId);
            Set<UUID> recovered = new HashSet<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    recovered.add(results.getObject("network_session_id", UUID.class));
                }
            }
            return recovered;
        }
    }

    private static Set<UUID> reactivateSessions(
            Connection connection,
            String backendId,
            Array sessionIds
    ) throws SQLException {
        String sql = """
                UPDATE player_sessions
                SET status = 'ACTIVE',
                    last_heartbeat_at = NOW()
                WHERE network_session_id = ANY (?)
                  AND owner_backend_id = ?
                  AND status = 'TRANSFERRING'
                  AND lease_expires_at > NOW()
                RETURNING network_session_id
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, sessionIds);
            statement.setString(2, backendId);
            Set<UUID> recovered = new HashSet<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    recovered.add(results.getObject("network_session_id", UUID.class));
                }
            }
            return recovered;
        }
    }

    private static Set<UUID> normalizedSessionIds(Collection<UUID> sessionIds) {
        Objects.requireNonNull(sessionIds, "attachedSessionIds");
        Set<UUID> normalized = new HashSet<>();
        for (UUID sessionId : sessionIds) {
            normalized.add(Objects.requireNonNull(sessionId, "attachedSessionIds must not contain null"));
        }
        return normalized;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
