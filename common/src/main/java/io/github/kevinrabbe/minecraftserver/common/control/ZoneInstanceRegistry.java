package io.github.kevinrabbe.minecraftserver.common.control;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Shared registry for live copies of logical gameplay zones. */
public final class ZoneInstanceRegistry {
    private final DataSource dataSource;

    public ZoneInstanceRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void registerStarting(
            UUID instanceId,
            String zoneId,
            String templateVersion,
            String backendId,
            int softCapacity,
            int hardCapacity
    ) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
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
                    status,
                    player_count,
                    soft_capacity,
                    hard_capacity,
                    started_at,
                    last_heartbeat_at
                ) VALUES (?, ?, ?, ?, 'STARTING', 0, ?, ?, NOW(), NOW())
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, instanceId);
            statement.setString(2, normalizedZoneId);
            statement.setString(3, normalizedTemplateVersion);
            statement.setString(4, normalizedBackendId);
            statement.setInt(5, softCapacity);
            statement.setInt(6, hardCapacity);
            statement.executeUpdate();
        }
    }

    public void heartbeat(UUID instanceId, ZoneInstanceStatus status, int playerCount) throws SQLException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(status, "status");
        if (playerCount < 0) {
            throw new IllegalArgumentException("playerCount must not be negative");
        }

        String sql = """
                UPDATE zone_instances
                SET status = ?, player_count = ?, last_heartbeat_at = NOW()
                WHERE instance_id = ?
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setInt(2, playerCount);
            statement.setObject(3, instanceId);
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new SQLException("Zone instance is not registered: " + instanceId);
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
