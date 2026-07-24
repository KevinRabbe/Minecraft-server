package io.github.kevinrabbe.minecraftserver.common.session;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Backend-facing lease operations for the sessions that are actually attached to players on that backend.
 *
 * <p>Callers must pass their locally attached session IDs. This deliberately avoids heartbeating abandoned
 * sessions merely because the database still names the backend as their last owner.</p>
 */
public final class BackendSessionLeaseRepository {
    private final DataSource dataSource;

    public BackendSessionLeaseRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Extends only still-valid leases owned by {@code backendId}. Expired, moved, or disconnected sessions are
     * omitted from the returned set and are never revived.
     */
    public Set<UUID> heartbeatAttachedSessions(
            String backendId,
            Collection<UUID> sessionIds,
            Duration leaseDuration
    ) throws SQLException {
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        Objects.requireNonNull(sessionIds, "sessionIds");
        long leaseMillis = requirePositiveDurationMillis(leaseDuration, "leaseDuration");
        Set<UUID> requested = normalizedSessionIds(sessionIds);
        if (requested.isEmpty()) {
            return Set.of();
        }

        String sql = """
                UPDATE player_sessions
                SET last_heartbeat_at = NOW(),
                    lease_expires_at = NOW() + (? * INTERVAL '1 millisecond')
                WHERE owner_backend_id = ?
                  AND network_session_id = ANY (?)
                  AND status IN ('ACTIVE', 'TRANSFERRING', 'RECOVERING')
                  AND lease_expires_at > NOW()
                RETURNING network_session_id
                """;

        try (Connection connection = dataSource.getConnection();
             Array sessionArray = connection.createArrayOf("uuid", requested.toArray());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, leaseMillis);
            statement.setString(2, normalizedBackendId);
            statement.setArray(3, sessionArray);

            Set<UUID> renewed = new HashSet<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    renewed.add(results.getObject("network_session_id", UUID.class));
                }
            }
            return Set.copyOf(renewed);
        }
    }

    /**
     * Releases one ordinary live session. A session already in TRANSFERRING is intentionally left alone so a
     * source-backend quit cannot cancel an in-flight cross-backend handoff.
     */
    public boolean disconnectAttachedSession(UUID sessionId, String backendId) throws SQLException {
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
                  AND status IN ('ACTIVE', 'RECOVERING')
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sessionId);
            statement.setString(2, normalizedBackendId);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Releases ordinary attached sessions during controlled backend shutdown. TRANSFERRING sessions remain
     * fenced for the target to claim; if no target claims them their leases/tickets expire naturally.
     */
    public Set<UUID> disconnectAttachedSessions(String backendId, Collection<UUID> sessionIds) throws SQLException {
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        Objects.requireNonNull(sessionIds, "sessionIds");
        Set<UUID> requested = normalizedSessionIds(sessionIds);
        if (requested.isEmpty()) {
            return Set.of();
        }

        String sql = """
                UPDATE player_sessions
                SET owner_backend_id = NULL,
                    owner_instance_id = NULL,
                    status = 'DISCONNECTED',
                    lease_expires_at = NULL,
                    last_heartbeat_at = NOW(),
                    disconnected_at = NOW()
                WHERE owner_backend_id = ?
                  AND network_session_id = ANY (?)
                  AND status IN ('ACTIVE', 'RECOVERING')
                RETURNING network_session_id
                """;

        try (Connection connection = dataSource.getConnection();
             Array sessionArray = connection.createArrayOf("uuid", requested.toArray());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedBackendId);
            statement.setArray(2, sessionArray);

            Set<UUID> disconnected = new HashSet<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    disconnected.add(results.getObject("network_session_id", UUID.class));
                }
            }
            return Set.copyOf(disconnected);
        }
    }

    private static Set<UUID> normalizedSessionIds(Collection<UUID> sessionIds) {
        Set<UUID> normalized = new HashSet<>();
        for (UUID sessionId : sessionIds) {
            normalized.add(Objects.requireNonNull(sessionId, "sessionIds must not contain null"));
        }
        return normalized;
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
}
