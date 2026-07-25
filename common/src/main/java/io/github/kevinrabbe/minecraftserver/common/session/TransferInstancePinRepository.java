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

/** Pins an open transfer ticket to one exact healthy zone instance. */
public final class TransferInstancePinRepository {
    private final DataSource dataSource;
    private final Duration heartbeatFreshness;

    public TransferInstancePinRepository(DataSource dataSource, Duration heartbeatFreshness) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.heartbeatFreshness = Objects.requireNonNull(heartbeatFreshness, "heartbeatFreshness");
        if (heartbeatFreshness.isZero() || heartbeatFreshness.isNegative()) {
            throw new IllegalArgumentException("heartbeatFreshness must be positive");
        }
    }

    /**
     * Idempotently pins an unrouted, open transfer to the requested exact backend/instance.
     * A previously pinned transfer may replay only for the same target.
     */
    public RoutedTransfer pin(UUID transferId, String targetBackendId, UUID targetInstanceId) throws SQLException {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        String backend = requireNonBlank(targetBackendId, "targetBackendId");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                OpenTransfer transfer = lockOpenTransfer(connection, transferId);
                if (transfer.pinnedInstance()) {
                    if (!backend.equals(transfer.targetBackendId())
                            || !targetInstanceId.equals(transfer.targetInstanceId())) {
                        throw new SessionConflictException(
                                "Transfer is already pinned to another instance: " + transferId
                        );
                    }
                    RoutedTransfer replay = toRoutedTransfer(transfer);
                    connection.commit();
                    return replay;
                }
                if (transfer.targetBackendId() != null || transfer.targetInstanceId() != null) {
                    throw new SessionConflictException(
                            "Transfer is already routed and cannot be converted to an exact-instance transfer: " + transferId
                    );
                }

                requireHealthyExactTarget(
                        connection,
                        targetInstanceId,
                        backend,
                        transfer.targetZoneId()
                );

                RoutedTransfer result;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE transfer_tickets
                        SET target_backend_id = ?,
                            target_instance_id = ?,
                            routed_at = NOW(),
                            pinned_instance = TRUE
                        WHERE transfer_id = ?
                          AND consumed_at IS NULL
                          AND expires_at > NOW()
                          AND target_backend_id IS NULL
                          AND target_instance_id IS NULL
                          AND pinned_instance = FALSE
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
                    statement.setString(1, backend);
                    statement.setObject(2, targetInstanceId);
                    statement.setObject(3, transferId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) {
                            throw new SessionConflictException("Transfer changed concurrently while pinning: " + transferId);
                        }
                        result = readRoutedTransfer(row, transferId);
                    }
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private OpenTransfer lockOpenTransfer(Connection connection, UUID transferId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tt.network_session_id,
                       tt.player_id,
                       tt.source_backend_id,
                       tt.target_zone_id,
                       tt.target_backend_id,
                       tt.target_instance_id,
                       tt.expected_state_version,
                       tt.expires_at,
                       tt.routed_at,
                       tt.pinned_instance
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
                """)) {
            statement.setObject(1, transferId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SessionConflictException(
                            "Transfer ticket is missing, stale, consumed, expired, or no longer owns a live transfer session: "
                                    + transferId
                    );
                }
                return new OpenTransfer(
                        transferId,
                        row.getObject("network_session_id", UUID.class),
                        row.getObject("player_id", UUID.class),
                        row.getString("source_backend_id"),
                        row.getString("target_zone_id"),
                        row.getString("target_backend_id"),
                        row.getObject("target_instance_id", UUID.class),
                        row.getLong("expected_state_version"),
                        row.getTimestamp("expires_at").toInstant(),
                        toInstant(row.getTimestamp("routed_at")),
                        row.getBoolean("pinned_instance")
                );
            }
        }
    }

    private void requireHealthyExactTarget(
            Connection connection,
            UUID instanceId,
            String backendId,
            String targetZoneId
    ) throws SQLException {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(heartbeatFreshness));
        try (PreparedStatement statement = connection.prepareStatement("""
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
                FOR UPDATE OF zi
                """)) {
            statement.setObject(1, instanceId);
            statement.setString(2, backendId);
            statement.setString(3, targetZoneId);
            statement.setTimestamp(4, cutoff);
            statement.setTimestamp(5, cutoff);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SessionConflictException(
                            "Exact transfer target is not a healthy available instance: " + instanceId
                    );
                }
            }
        }
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

    private static RoutedTransfer readRoutedTransfer(ResultSet row, UUID transferId) throws SQLException {
        return new RoutedTransfer(
                transferId,
                row.getObject("network_session_id", UUID.class),
                row.getObject("player_id", UUID.class),
                row.getString("source_backend_id"),
                row.getString("target_zone_id"),
                row.getString("target_backend_id"),
                row.getObject("target_instance_id", UUID.class),
                row.getLong("expected_state_version"),
                row.getTimestamp("expires_at").toInstant(),
                row.getTimestamp("routed_at").toInstant()
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
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
            Instant routedAt,
            boolean pinnedInstance
    ) { }
}
