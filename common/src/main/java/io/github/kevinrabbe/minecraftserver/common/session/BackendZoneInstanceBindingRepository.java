package io.github.kevinrabbe.minecraftserver.common.session;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the one fresh ACTIVE zone instance physically represented by a bootstrap Paper backend.
 *
 * <p>This is deliberately a single-instance bootstrap contract. If a future backend can physically host multiple
 * simultaneously addressable copies of the same logical zone, login routing must carry the selected instance identity
 * explicitly instead of guessing between them here.</p>
 */
public final class BackendZoneInstanceBindingRepository {
    private final DataSource dataSource;
    private final long heartbeatFreshnessMillis;

    public BackendZoneInstanceBindingRepository(DataSource dataSource, Duration heartbeatFreshness) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(heartbeatFreshness, "heartbeatFreshness");
        if (heartbeatFreshness.isZero() || heartbeatFreshness.isNegative()) {
            throw new IllegalArgumentException("heartbeatFreshness must be positive");
        }
        long freshnessMillis = heartbeatFreshness.toMillis();
        if (freshnessMillis < 1) {
            throw new IllegalArgumentException("heartbeatFreshness must be at least 1 millisecond");
        }
        this.heartbeatFreshnessMillis = freshnessMillis;
    }

    public Optional<UUID> findSingleFreshActiveInstance(String backendId, String zoneId) throws SQLException {
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        String normalizedZoneId = requireNonBlank(zoneId, "zoneId");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT instance_id
                     FROM zone_instances
                     WHERE backend_id = ?
                       AND zone_id = ?
                       AND status = 'ACTIVE'
                       AND last_heartbeat_at >= NOW() - (? * INTERVAL '1 millisecond')
                     ORDER BY instance_id
                     LIMIT 2
                     """)) {
            statement.setString(1, normalizedBackendId);
            statement.setString(2, normalizedZoneId);
            statement.setLong(3, heartbeatFreshnessMillis);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                UUID instanceId = rows.getObject("instance_id", UUID.class);
                if (rows.next()) {
                    throw new SessionConflictException(
                            "Bootstrap backend " + normalizedBackendId + " has multiple fresh ACTIVE instances for zone "
                                    + normalizedZoneId
                    );
                }
                return Optional.of(instanceId);
            }
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
