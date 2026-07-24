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
import java.util.Optional;
import java.util.UUID;

/** Assigns one open logical-zone transfer to one healthy live zone instance. */
public final class TransferRoutingRepository {
    private final DataSource dataSource;
    private final Duration heartbeatFreshness;

    public TransferRoutingRepository(DataSource dataSource, Duration heartbeatFreshness) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.heartbeatFreshness = Objects.requireNonNull(heartbeatFreshness, "heartbeatFreshness");
        if (heartbeatFreshness.isZero() || heartbeatFreshness.isNegative()) {
            throw new IllegalArgumentException("heartbeatFreshness must be positive");
        }
    }

    /** Finds the player's current open transfer and routes it if a healthy target exists. */
    public Optional<RoutedTransfer> routeForPlayer(UUID minecraftUuid) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");

        String sql = """
                SELECT tt.transfer_id
                FROM transfer_tickets tt
                JOIN players p ON p.player_id = tt.player_id
                JOIN player_sessions ps ON ps.network_session_id = tt.network_session_id
                WHERE p.minecraft_uuid = ?
                  AND tt.consumed_at IS NULL
                  AND tt.expires_at > NOW()
                  AND ps.status = 'TRANSFERRING'
                  AND ps.owner_backend_id = tt.source_backend_id
                  AND ps.state_version = tt.expected_state_version
                  AND ps.lease_expires_at > NOW()
                ORDER BY tt.created_at DESC
                LIMIT 1
                """;

        UUID transferId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                transferId = results.getObject("transfer_id", UUID.class);
            }
        }

        return route(transferId);
    }

    /**
     * Returns a still-healthy existing route or atomically chooses a new healthy target.
     * Empty means no suitable live instance is currently available.
     */
    public Optional<RoutedTransfer> route(UUID transferId) throws SQLException {
        Objects.requireNonNull(transferId, "transferId");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                OpenTransfer transfer = lockOpenTransfer(connection, transferId);
                if (transfer.targetBackendId() != null
                        && targetIsHealthy(
                        connection,
                        transfer.targetInstanceId(),
                        transfer.targetBackendId(),
                        transfer.targetZoneId()
                )) {
                    RoutedTransfer existing = toRoutedTransfer(transfer);
                    connection.commit();
                    return Optional.of(existing);
                }

                if (transfer.targetBackendId() != null) {
                    clearRoute(connection, transferId);
                }

                Target target = chooseTarget(connection, transfer.targetZoneId());
                if (target == null) {
                    connection.commit();
                    return Optional.empty();
                }

                RoutedTransfer routed;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE transfer_tickets
                        SET target_backend_id = ?,
                            target_instance_id = ?,
                            routed_at = NOW()
                        WHERE transfer_id = ?
                          AND consumed_at IS NULL
                          AND expires_at > NOW()
                          AND target_backend_id IS NULL
                          AND target_instance_id IS NULL
                        RETURNING network_session_id,
                                  player_id,
                                  source_backend_id,
                                  target_zone_id,
                                  target_backend_id,
                                  target_instance_id,
                                  expected_state_version,
                                  expires_at,
                                  routed_at
                        """)) {
                    statement.setString(1, target.backendId());
                    statement.setObject(2, target.instanceId());
                    statement.setObject(3, transferId);

                    try (ResultSet results = statement.executeQuery()) {
                        if (!results.next()) {
                            throw new SessionConflictException("Transfer changed while routing: " + transferId);
                        }
                        routed = readRoutedTransfer(results, transferId);
                    }
                }

                connection.commit();
                return Optional.of(routed);
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Finds an unconsumed, unexpired routed transfer by the authenticated Minecraft UUID. */
    public Optional<RoutedTransfer> findRoutedTransfer(UUID minecraftUuid) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");

        String sql = """
                SELECT tt.transfer_id,
                       tt.network_session_id,
                       tt.player_id,
                       tt.source_backend_id,
                       tt.target_zone_id,
                       tt.target_backend_id,
                       tt.target_instance_id,
                       tt.expected_state_version,
                       tt.expires_at,
                       tt.routed_at
                FROM transfer_tickets tt
                JOIN players p ON p.player_id = tt.player_id
                JOIN player_sessions ps ON ps.network_session_id = tt.network_session_id
                WHERE p.minecraft_uuid = ?
                  AND tt.consumed_at IS NULL
                  AND tt.expires_at > NOW()
                  AND tt.target_backend_id IS NOT NULL
                  AND tt.target_instance_id IS NOT NULL
                  AND ps.status = 'TRANSFERRING'
                  AND ps.owner_backend_id = tt.source_backend_id
                  AND ps.state_version = tt.expected_state_version
                  AND ps.lease_expires_at > NOW()
                ORDER BY tt.created_at DESC
                LIMIT 1
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                UUID transferId = results.getObject("transfer_id", UUID.class);
                return Optional.of(readRoutedTransfer(results, transferId));
            }
        }
    }

    private OpenTransfer lockOpenTransfer(Connection connection, UUID transferId) throws SQLException {
        String sql = """
                SELECT tt.network_session_id,
                       tt.player_id,
                       tt.source_backend_id,
                       tt.target_zone_id,
                       tt.target_backend_id,
                       tt.target_instance_id,
                       tt.expected_state_version,
                       tt.expires_at,
                       tt.routed_at
                FROM transfer_tickets tt
                JOIN player_sessions ps ON ps.network_session_id = tt.network_session_id
                WHERE tt.transfer_id = ?
                  AND tt.consumed_at IS NULL
                  AND tt.expires_at > NOW()
                  AND ps.status = 'TRANSFERRING'
                  AND ps.owner_backend_id = tt.source_backend_id
                  AND ps.state_version = tt.expected_state_version
                  AND ps.lease_expires_at > NOW()
                FOR UPDATE OF tt, ps
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, transferId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SessionConflictException(
                            "Transfer ticket is missing, stale, consumed, expired, or no longer owns a live transfer session: "
                                    + transferId
                    );
                }
                return new OpenTransfer(
                        transferId,
                        results.getObject("network_session_id", UUID.class),
                        results.getObject("player_id", UUID.class),
                        results.getString("source_backend_id"),
                        results.getString("target_zone_id"),
                        results.getString("target_backend_id"),
                        results.getObject("target_instance_id", UUID.class),
                        results.getLong("expected_state_version"),
                        results.getTimestamp("expires_at").toInstant(),
                        toInstant(results.getTimestamp("routed_at"))
                );
            }
        }
    }

    private Target chooseTarget(Connection connection, String targetZoneId) throws SQLException {
        Timestamp cutoff = freshnessCutoff();
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
                FOR UPDATE OF zi
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetZoneId);
            statement.setTimestamp(2, cutoff);
            statement.setTimestamp(3, cutoff);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return null;
                }
                return new Target(
                        results.getObject("instance_id", UUID.class),
                        results.getString("backend_id")
                );
            }
        }
    }

    private boolean targetIsHealthy(
            Connection connection,
            UUID instanceId,
            String backendId,
            String targetZoneId
    ) throws SQLException {
        Timestamp cutoff = freshnessCutoff();
        String sql = """
                SELECT 1
                FROM zone_instances zi
                JOIN backends b ON b.backend_id = zi.backend_id
                WHERE zi.instance_id = ?
                  AND zi.backend_id = ?
                  AND zi.zone_id = ?
                  AND zi.status = 'ACTIVE'
                  AND b.status = 'ONLINE'
                  AND zi.player_count < zi.hard_capacity
                  AND zi.last_heartbeat_at >= ?
                  AND b.last_heartbeat_at >= ?
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, instanceId);
            statement.setString(2, backendId);
            statement.setString(3, targetZoneId);
            statement.setTimestamp(4, cutoff);
            statement.setTimestamp(5, cutoff);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static void clearRoute(Connection connection, UUID transferId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transfer_tickets
                SET target_backend_id = NULL,
                    target_instance_id = NULL,
                    routed_at = NULL
                WHERE transfer_id = ?
                  AND consumed_at IS NULL
                """)) {
            statement.setObject(1, transferId);
            if (statement.executeUpdate() != 1) {
                throw new SessionConflictException("Transfer changed while clearing stale route: " + transferId);
            }
        }
    }

    private Timestamp freshnessCutoff() {
        return Timestamp.from(Instant.now().minus(heartbeatFreshness));
    }

    private static RoutedTransfer toRoutedTransfer(OpenTransfer transfer) {
        return new RoutedTransfer(
                transfer.transferId(),
                transfer.sessionId(),
                transfer.playerId(),
                transfer.sourceBackendId(),
                transfer.targetZoneId(),
                transfer.targetBackendId(),
                transfer.targetInstanceId(),
                transfer.expectedStateVersion(),
                transfer.expiresAt(),
                transfer.routedAt()
        );
    }

    private static RoutedTransfer readRoutedTransfer(ResultSet results, UUID transferId) throws SQLException {
        return new RoutedTransfer(
                transferId,
                results.getObject("network_session_id", UUID.class),
                results.getObject("player_id", UUID.class),
                results.getString("source_backend_id"),
                results.getString("target_zone_id"),
                results.getString("target_backend_id"),
                results.getObject("target_instance_id", UUID.class),
                results.getLong("expected_state_version"),
                results.getTimestamp("expires_at").toInstant(),
                results.getTimestamp("routed_at").toInstant()
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record Target(UUID instanceId, String backendId) {
    }

    private record OpenTransfer(
            UUID transferId,
            UUID sessionId,
            UUID playerId,
            String sourceBackendId,
            String targetZoneId,
            String targetBackendId,
            UUID targetInstanceId,
            long expectedStateVersion,
            Instant expiresAt,
            Instant routedAt
    ) {
    }
}
