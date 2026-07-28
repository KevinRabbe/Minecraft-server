package io.github.kevinrabbe.minecraftserver.common.verification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only bounded verification of single-writer session/transfer representations against persistent player state. */
public final class PlayerSessionIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public PlayerSessionIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyLiveSessionStateVersion(connection, issues, maxIssues);
            verifySessionLifecycleShape(connection, issues, maxIssues);
            verifyLiveSessionOwnerInstanceIdentity(connection, issues, maxIssues);
            verifyTransferringSessionTicket(connection, issues, maxIssues);
            verifyOpenTicketSessionStatus(connection, issues, maxIssues);
            verifyRoutedTransferTargetIdentity(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyLiveSessionStateVersion(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT session.network_session_id,
                       session.player_id,
                       session.status,
                       session.state_version AS session_state_version,
                       state.state_version AS player_state_version,
                       state.player_id IS NULL AS missing_player_state
                FROM player_sessions session
                LEFT JOIN player_state state ON state.player_id = session.player_id
                WHERE session.status IN ('ACTIVE', 'TRANSFERRING', 'RECOVERING')
                  AND (
                      state.player_id IS NULL
                      OR session.state_version IS DISTINCT FROM state.state_version
                  )
                ORDER BY session.network_session_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID sessionId = rows.getObject("network_session_id", UUID.class);
                    boolean missingState = rows.getBoolean("missing_player_state");
                    String playerStateVersion = missingState
                            ? "missing"
                            : Long.toString(rows.getLong("player_state_version"));
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "LIVE_SESSION_STATE_VERSION_MISMATCH",
                            sessionId.toString(),
                            "Live " + rows.getString("status") + " session for player "
                                    + rows.getObject("player_id", UUID.class) + " carries stateVersion="
                                    + rows.getLong("session_state_version") + " while player_state="
                                    + playerStateVersion
                    ));
                }
            }
        }
    }

    private static void verifySessionLifecycleShape(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT network_session_id,
                       player_id,
                       status,
                       owner_backend_id,
                       owner_instance_id,
                       lease_expires_at
                FROM player_sessions
                WHERE (
                    status IN ('ACTIVE', 'TRANSFERRING', 'RECOVERING')
                    AND (
                        owner_backend_id IS NULL
                        OR BTRIM(owner_backend_id) = ''
                        OR lease_expires_at IS NULL
                    )
                ) OR (
                    status = 'DISCONNECTED'
                    AND (
                        owner_backend_id IS NOT NULL
                        OR owner_instance_id IS NOT NULL
                        OR lease_expires_at IS NOT NULL
                    )
                )
                ORDER BY network_session_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID sessionId = rows.getObject("network_session_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "SESSION_LIFECYCLE_SHAPE_INVALID",
                            sessionId.toString(),
                            "Session for player " + rows.getObject("player_id", UUID.class)
                                    + " has status=" + rows.getString("status")
                                    + ", ownerBackend=" + rows.getString("owner_backend_id")
                                    + ", ownerInstance=" + rows.getObject("owner_instance_id", UUID.class)
                                    + ", leaseExpiresAt=" + rows.getTimestamp("lease_expires_at")
                    ));
                }
            }
        }
    }

    private static void verifyLiveSessionOwnerInstanceIdentity(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT session.network_session_id,
                       session.player_id,
                       session.status,
                       session.owner_backend_id,
                       session.owner_instance_id,
                       instance.backend_id AS instance_backend_id,
                       instance.zone_id AS instance_zone_id
                FROM player_sessions session
                LEFT JOIN zone_instances instance ON instance.instance_id = session.owner_instance_id
                WHERE session.status IN ('ACTIVE', 'TRANSFERRING', 'RECOVERING')
                  AND session.owner_instance_id IS NOT NULL
                  AND (
                      instance.instance_id IS NULL
                      OR session.owner_backend_id IS DISTINCT FROM instance.backend_id
                  )
                ORDER BY session.network_session_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID sessionId = rows.getObject("network_session_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "LIVE_SESSION_INSTANCE_IDENTITY_MISMATCH",
                            sessionId.toString(),
                            "Live " + rows.getString("status") + " session for player "
                                    + rows.getObject("player_id", UUID.class) + " claims ownerBackend="
                                    + rows.getString("owner_backend_id") + " and ownerInstance="
                                    + rows.getObject("owner_instance_id", UUID.class)
                                    + " but that stable instance belongs to backend="
                                    + rows.getString("instance_backend_id") + ", zone="
                                    + rows.getString("instance_zone_id")
                    ));
                }
            }
        }
    }

    private static void verifyTransferringSessionTicket(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT session.network_session_id,
                       session.player_id AS session_player_id,
                       session.owner_backend_id,
                       session.state_version AS session_state_version,
                       ticket.transfer_id,
                       ticket.player_id AS ticket_player_id,
                       ticket.source_backend_id,
                       ticket.target_zone_id,
                       ticket.expected_state_version
                FROM player_sessions session
                LEFT JOIN transfer_tickets ticket
                  ON ticket.network_session_id = session.network_session_id
                 AND ticket.consumed_at IS NULL
                WHERE session.status = 'TRANSFERRING'
                  AND (
                      ticket.transfer_id IS NULL
                      OR ticket.player_id IS DISTINCT FROM session.player_id
                      OR ticket.source_backend_id IS DISTINCT FROM session.owner_backend_id
                      OR ticket.expected_state_version IS DISTINCT FROM session.state_version
                      OR BTRIM(ticket.source_backend_id) = ''
                      OR BTRIM(ticket.target_zone_id) = ''
                  )
                ORDER BY session.network_session_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID sessionId = rows.getObject("network_session_id", UUID.class);
                    UUID transferId = rows.getObject("transfer_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "TRANSFERRING_SESSION_TICKET_MISMATCH",
                            sessionId.toString(),
                            "TRANSFERRING session does not match its open transfer ticket: transfer=" + transferId
                                    + ", sessionPlayer=" + rows.getObject("session_player_id", UUID.class)
                                    + ", ticketPlayer=" + rows.getObject("ticket_player_id", UUID.class)
                                    + ", ownerBackend=" + rows.getString("owner_backend_id")
                                    + ", sourceBackend=" + rows.getString("source_backend_id")
                                    + ", sessionVersion=" + rows.getLong("session_state_version")
                                    + ", expectedVersion=" + nullableLong(rows, "expected_state_version")
                                    + ", targetZone=" + rows.getString("target_zone_id")
                    ));
                }
            }
        }
    }

    private static void verifyOpenTicketSessionStatus(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ticket.transfer_id,
                       ticket.network_session_id,
                       ticket.player_id,
                       session.status
                FROM transfer_tickets ticket
                JOIN player_sessions session ON session.network_session_id = ticket.network_session_id
                WHERE ticket.consumed_at IS NULL
                  AND session.status <> 'TRANSFERRING'
                ORDER BY ticket.transfer_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID transferId = rows.getObject("transfer_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "OPEN_TRANSFER_TICKET_SESSION_MISMATCH",
                            transferId.toString(),
                            "Open transfer ticket for player " + rows.getObject("player_id", UUID.class)
                                    + " references session " + rows.getObject("network_session_id", UUID.class)
                                    + " with status " + rows.getString("status")
                    ));
                }
            }
        }
    }

    private static void verifyRoutedTransferTargetIdentity(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ticket.transfer_id,
                       ticket.target_zone_id,
                       ticket.target_backend_id,
                       ticket.target_instance_id,
                       instance.zone_id AS instance_zone_id,
                       instance.backend_id AS instance_backend_id
                FROM transfer_tickets ticket
                LEFT JOIN zone_instances instance ON instance.instance_id = ticket.target_instance_id
                WHERE ticket.target_instance_id IS NOT NULL
                  AND (
                      instance.instance_id IS NULL
                      OR ticket.target_backend_id IS DISTINCT FROM instance.backend_id
                      OR ticket.target_zone_id IS DISTINCT FROM instance.zone_id
                  )
                ORDER BY ticket.transfer_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID transferId = rows.getObject("transfer_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "ROUTED_TRANSFER_TARGET_IDENTITY_MISMATCH",
                            transferId.toString(),
                            "Routed transfer target does not match its stable zone-instance identity: ticketZone="
                                    + rows.getString("target_zone_id")
                                    + ", ticketBackend=" + rows.getString("target_backend_id")
                                    + ", instance=" + rows.getObject("target_instance_id", UUID.class)
                                    + ", instanceZone=" + rows.getString("instance_zone_id")
                                    + ", instanceBackend=" + rows.getString("instance_backend_id")
                    ));
                }
            }
        }
    }

    private static String nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? "missing" : Long.toString(value);
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
