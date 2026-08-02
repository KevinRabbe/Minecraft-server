package io.github.kevinrabbe.minecraftserver.common.control;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared control-plane registry for Paper backend health and capacity signals. */
public final class BackendRegistry {
    private static final Map<String, UUID> PROCESS_INCARNATIONS = new ConcurrentHashMap<>();
    private static volatile DataSource processDataSource;

    private final DataSource dataSource;
    private final Map<String, UUID> registeredIncarnations = new ConcurrentHashMap<>();

    public BackendRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        installProcessDataSource(dataSource);
    }

    public UUID registerStarting(String backendId) throws SQLException {
        String normalizedBackendId = requireBackendId(backendId);
        UUID incarnationId = UUID.randomUUID();
        String sql = """
                INSERT INTO backends (backend_id, incarnation_id, status, started_at, last_heartbeat_at, player_count)
                VALUES (?, ?, 'STARTING', NOW(), NOW(), 0)
                ON CONFLICT (backend_id) DO UPDATE SET
                    incarnation_id = EXCLUDED.incarnation_id,
                    status = 'STARTING',
                    started_at = NOW(),
                    last_heartbeat_at = NOW(),
                    player_count = 0
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedBackendId);
            statement.setObject(2, incarnationId);
            statement.executeUpdate();
        }
        registeredIncarnations.put(normalizedBackendId, incarnationId);
        PROCESS_INCARNATIONS.put(normalizedBackendId, incarnationId);
        return incarnationId;
    }

    public void publishOnline(String backendId, int playerCount) throws SQLException {
        String normalizedBackendId = requireBackendId(backendId);
        requirePlayerCount(playerCount);
        UUID incarnationId = requireRegisteredIncarnation(normalizedBackendId);
        String sql = """
                UPDATE backends
                SET status = 'ONLINE', last_heartbeat_at = NOW(), player_count = ?
                WHERE backend_id = ? AND incarnation_id = ? AND status IN ('STARTING', 'ONLINE')
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, playerCount);
            statement.setString(2, normalizedBackendId);
            statement.setObject(3, incarnationId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Backend incarnation is not awaiting online publication: " + normalizedBackendId);
            }
        }
    }

    public UUID registerOnline(String backendId, int playerCount) throws SQLException {
        String normalizedBackendId = requireBackendId(backendId);
        requirePlayerCount(playerCount);
        UUID incarnationId = UUID.randomUUID();
        String sql = """
                INSERT INTO backends (backend_id, incarnation_id, status, started_at, last_heartbeat_at, player_count)
                VALUES (?, ?, 'ONLINE', NOW(), NOW(), ?)
                ON CONFLICT (backend_id) DO UPDATE SET
                    incarnation_id = EXCLUDED.incarnation_id,
                    status = 'ONLINE',
                    started_at = NOW(),
                    last_heartbeat_at = NOW(),
                    player_count = EXCLUDED.player_count
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedBackendId);
            statement.setObject(2, incarnationId);
            statement.setInt(3, playerCount);
            statement.executeUpdate();
        }
        registeredIncarnations.put(normalizedBackendId, incarnationId);
        PROCESS_INCARNATIONS.put(normalizedBackendId, incarnationId);
        return incarnationId;
    }

    public void heartbeat(String backendId, int playerCount) throws SQLException {
        String normalizedBackendId = requireBackendId(backendId);
        requirePlayerCount(playerCount);
        UUID incarnationId = requireRegisteredIncarnation(normalizedBackendId);
        String sql = """
                UPDATE backends
                SET last_heartbeat_at = NOW(), player_count = ?, status = 'ONLINE'
                WHERE backend_id = ? AND incarnation_id = ? AND status IN ('ONLINE', 'DRAINING')
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, playerCount);
            statement.setString(2, normalizedBackendId);
            statement.setObject(3, incarnationId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Backend incarnation is not heartbeat-eligible: " + normalizedBackendId);
            }
        }
    }

    public void markDraining(String backendId) throws SQLException {
        updateStatus(backendId, BackendStatus.DRAINING);
    }

    public void markOffline(String backendId) throws SQLException {
        String normalizedBackendId = requireBackendId(backendId);
        UUID incarnationId = updateStatus(normalizedBackendId, BackendStatus.OFFLINE);
        PROCESS_INCARNATIONS.remove(normalizedBackendId, incarnationId);
    }

    private UUID updateStatus(String backendId, BackendStatus status) throws SQLException {
        String normalizedBackendId = requireBackendId(backendId);
        UUID incarnationId = requireRegisteredIncarnation(normalizedBackendId);
        String sql = "UPDATE backends SET status = ?, last_heartbeat_at = NOW() WHERE backend_id = ? AND incarnation_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, normalizedBackendId);
            statement.setObject(3, incarnationId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Backend incarnation no longer owns status authority: " + normalizedBackendId);
            }
        }
        return incarnationId;
    }

    /**
     * Returns the token registered by this JVM. Test and migration fixtures that create backend rows directly fall
     * back to the durable current token only when this JVM has never registered that backend. A replaced JVM retains
     * its old process-local token and therefore cannot adopt the replacement token through this path.
     */
    public static UUID requireProcessIncarnation(String backendId) throws SQLException {
        String normalizedBackendId = requireBackendId(backendId);
        UUID incarnationId = PROCESS_INCARNATIONS.get(normalizedBackendId);
        if (incarnationId != null) {
            return incarnationId;
        }
        DataSource fallback = processDataSource;
        if (fallback == null) {
            throw new SQLException("Backend has no process-local registered incarnation: " + normalizedBackendId);
        }
        try (Connection connection = fallback.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT incarnation_id FROM backends WHERE backend_id = ? AND status IN ('STARTING', 'ONLINE', 'DRAINING')"
             )) {
            statement.setString(1, normalizedBackendId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Backend has no current durable incarnation: " + normalizedBackendId);
                }
                return results.getObject("incarnation_id", UUID.class);
            }
        }
    }

    public static void installProcessDataSource(DataSource dataSource) {
        processDataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public static void clearProcessDataSource(DataSource dataSource) {
        if (processDataSource == dataSource) {
            processDataSource = null;
        }
    }

    private UUID requireRegisteredIncarnation(String backendId) throws SQLException {
        UUID incarnationId = registeredIncarnations.get(backendId);
        if (incarnationId == null) {
            throw new SQLException("Backend is not registered by this registry: " + backendId);
        }
        return incarnationId;
    }

    private static String requireBackendId(String backendId) {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        return backendId.trim();
    }

    private static void requirePlayerCount(int playerCount) {
        if (playerCount < 0) {
            throw new IllegalArgumentException("playerCount must not be negative");
        }
    }
}
