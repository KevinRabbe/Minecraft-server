package io.github.kevinrabbe.minecraftserver.common.control;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared registry for live copies of logical gameplay zones. */
public final class ZoneInstanceRegistry {
    private final DataSource dataSource;
    private final Map<UUID, UUID> registeredBackendIncarnations = new ConcurrentHashMap<>();

    public ZoneInstanceRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void registerStarting(
            UUID instanceId,
            String zoneId,
            String templateVersion,
            String backendId,
            UUID backendIncarnationId,
            int softCapacity,
            int hardCapacity
    ) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(backendIncarnationId, "backendIncarnationId");
        String normalizedZoneId = requireNonBlank(zoneId, "zoneId");
        String normalizedTemplateVersion = requireNonBlank(templateVersion, "templateVersion");
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        validateCapacity(softCapacity, hardCapacity);

        String sql = """
                INSERT INTO zone_instances (
                    instance_id,
                    zone_id,
                    template_version,
                    backend_id,
                    backend_incarnation_id,
                    status,
                    player_count,
                    soft_capacity,
                    hard_capacity,
                    started_at,
                    last_heartbeat_at
                )
                SELECT ?, ?, ?, backend.backend_id, ?, 'STARTING', 0, ?, ?, NOW(), NOW()
                FROM backends backend
                WHERE backend.backend_id = ?
                  AND backend.incarnation_id = ?
                  AND backend.status IN ('STARTING', 'ONLINE')
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, instanceId);
            statement.setString(2, normalizedZoneId);
            statement.setString(3, normalizedTemplateVersion);
            statement.setObject(4, backendIncarnationId);
            statement.setInt(5, softCapacity);
            statement.setInt(6, hardCapacity);
            statement.setString(7, normalizedBackendId);
            statement.setObject(8, backendIncarnationId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Backend incarnation is not zone-registration eligible: " + normalizedBackendId);
            }
        }
        registeredBackendIncarnations.put(instanceId, backendIncarnationId);
    }

    public void heartbeat(UUID instanceId, ZoneInstanceStatus status, int playerCount) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(status, "status");
        if (playerCount < 0) {
            throw new IllegalArgumentException("playerCount must not be negative");
        }
        UUID backendIncarnationId = registeredBackendIncarnations.get(instanceId);
        if (backendIncarnationId == null) {
            throw new SQLException("Zone instance is not registered by this registry: " + instanceId);
        }

        String sql = """
                UPDATE zone_instances
                SET status = ?, player_count = ?, last_heartbeat_at = NOW()
                WHERE instance_id = ?
                  AND backend_incarnation_id = ?
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setInt(2, playerCount);
            statement.setObject(3, instanceId);
            statement.setObject(4, backendIncarnationId);
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new SQLException("Zone instance incarnation no longer owns write authority: " + instanceId);
            }
        }
    }

    public void markStopped(UUID instanceId) throws SQLException {
        heartbeat(instanceId, ZoneInstanceStatus.STOPPED, 0);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static void validateCapacity(int softCapacity, int hardCapacity) {
        if (softCapacity < 1) {
            throw new IllegalArgumentException("softCapacity must be at least 1");
        }
        if (hardCapacity < softCapacity) {
            throw new IllegalArgumentException("hardCapacity must be greater than or equal to softCapacity");
        }
    }
}
