package io.github.kevinrabbe.minecraftserver.common.control;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/** Shared control-plane registry for Paper backend health and capacity signals. */
public final class BackendRegistry {
    private final DataSource dataSource;

    public BackendRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void registerOnline(String backendId, int playerCount) throws SQLException {
        requireBackendId(backendId);
        requirePlayerCount(playerCount);

        String sql = """
                INSERT INTO backends (
                    backend_id,
                    status,
                    started_at,
                    last_heartbeat_at,
                    player_count
                ) VALUES (?, 'ONLINE', NOW(), NOW(), ?)
                ON CONFLICT (backend_id) DO UPDATE SET
                    status = 'ONLINE',
                    started_at = NOW(),
                    last_heartbeat_at = NOW(),
                    player_count = EXCLUDED.player_count
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, backendId.trim());
            statement.setInt(2, playerCount);
            statement.executeUpdate();
        }
    }

    public void heartbeat(String backendId, int playerCount) throws SQLException {
        requireBackendId(backendId);
        requirePlayerCount(playerCount);

        String sql = """
                UPDATE backends
                SET last_heartbeat_at = NOW(), player_count = ?, status = 'ONLINE'
                WHERE backend_id = ?
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, playerCount);
            statement.setString(2, backendId.trim());
            int updated = statement.executeUpdate();
            if (updated != 1) {
                throw new SQLException("Backend is not registered: " + backendId);
            }
        }
    }

    public void markDraining(String backendId) throws SQLException {
        updateStatus(backendId, BackendStatus.DRAINING);
    }

    public void markOffline(String backendId) throws SQLException {
        updateStatus(backendId, BackendStatus.OFFLINE);
    }

    private void updateStatus(String backendId, BackendStatus status) throws SQLException {
        requireBackendId(backendId);

        String sql = """
                UPDATE backends
                SET status = ?, last_heartbeat_at = NOW()
                WHERE backend_id = ?
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, backendId.trim());
            statement.executeUpdate();
        }
    }

    private static void requireBackendId(String backendId) {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
    }

    private static void requirePlayerCount(int playerCount) {
        if (playerCount < 0) {
            throw new IllegalArgumentException("playerCount must not be negative");
        }
    }
}
