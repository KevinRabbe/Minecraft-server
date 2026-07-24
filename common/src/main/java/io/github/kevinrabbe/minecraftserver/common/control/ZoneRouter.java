package io.github.kevinrabbe.minecraftserver.common.control;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Resolves logical gameplay zones to healthy live instances without exposing backend IDs to gameplay code. */
public final class ZoneRouter {
    private final DataSource dataSource;
    private final Duration heartbeatFreshness;

    public ZoneRouter(DataSource dataSource, Duration heartbeatFreshness) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.heartbeatFreshness = Objects.requireNonNull(heartbeatFreshness, "heartbeatFreshness");
        if (heartbeatFreshness.isZero() || heartbeatFreshness.isNegative()) {
            throw new IllegalArgumentException("heartbeatFreshness must be positive");
        }
    }

    public Optional<ZoneRoute> findPreferredActiveInstance(String zoneId) throws SQLException {
        String normalizedZoneId = requireNonBlank(zoneId, "zoneId");
        Timestamp freshnessCutoff = Timestamp.from(Instant.now().minus(heartbeatFreshness));

        String sql = """
                SELECT zi.instance_id, zi.backend_id
                FROM zone_instances zi
                JOIN backends b ON b.backend_id = zi.backend_id
                WHERE zi.zone_id = ?
                  AND zi.status = 'ACTIVE'
                  AND b.status = 'ONLINE'
                  AND zi.player_count < zi.hard_capacity
                  AND zi.last_heartbeat_at >= ?
                  AND b.last_heartbeat_at >= ?
                ORDER BY
                    CASE WHEN zi.player_count < zi.soft_capacity THEN 0 ELSE 1 END,
                    zi.player_count DESC,
                    zi.started_at ASC
                LIMIT 1
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedZoneId);
            statement.setTimestamp(2, freshnessCutoff);
            statement.setTimestamp(3, freshnessCutoff);

            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }

                return Optional.of(new ZoneRoute(
                        normalizedZoneId,
                        results.getObject("instance_id", UUID.class),
                        results.getString("backend_id")
                ));
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
