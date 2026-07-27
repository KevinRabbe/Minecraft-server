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

/** Read-only bounded verification of the single-writer session representation against persistent player state. */
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
                    AND (owner_backend_id IS NULL OR lease_expires_at IS NULL)
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

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
