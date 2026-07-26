package io.github.kevinrabbe.minecraftserver.legacy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** JDBC client for the narrow SECURITY DEFINER runtime API. It never queries persistent-value tables directly. */
final class LegacyRuntimeDatabase {
    private final String jdbcUrl;
    private final String username;
    private final String password;

    LegacyRuntimeDatabase(String jdbcUrl, String username, String password) {
        this.jdbcUrl = requireText(jdbcUrl, "jdbcUrl");
        this.username = requireText(username, "username");
        this.password = Objects.requireNonNull(password, "password");
    }

    void initializeDriver() throws ClassNotFoundException {
        DriverManager.setLoginTimeout(5);
        Class.forName("org.postgresql.Driver");
    }

    String heartbeatBackend(int playerCount) throws SQLException {
        if (playerCount < 0) {
            throw new IllegalArgumentException("playerCount must be >= 0");
        }
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT competitive_runtime_heartbeat(?)")) {
            statement.setInt(1, playerCount);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("competitive_runtime_heartbeat returned no row");
                return row.getString(1);
            }
        }
    }

    String markOffline() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT competitive_runtime_mark_offline()")) {
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("competitive_runtime_mark_offline returned no row");
                return row.getString(1);
            }
        }
    }

    List<LegacyExecution> pollActive(int executionLimit) throws SQLException {
        if (executionLimit < 1 || executionLimit > 50) {
            throw new IllegalArgumentException("executionLimit must be between 1 and 50");
        }
        LinkedHashMap<UUID, ExecutionBuilder> builders = new LinkedHashMap<UUID, ExecutionBuilder>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM competitive_runtime_poll_active(?)")) {
            statement.setInt(1, executionLimit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID executionId = rows.getObject("execution_id", UUID.class);
                    ExecutionBuilder builder = builders.get(executionId);
                    if (builder == null) {
                        builder = new ExecutionBuilder(
                                executionId,
                                rows.getString("activity_kind"),
                                rows.getObject("activity_id", UUID.class),
                                rows.getLong("state_version"),
                                rows.getTimestamp("lease_expires_at").toInstant(),
                                rows.getString("ruleset_id"),
                                rows.getInt("ruleset_version"),
                                rows.getInt("team_size")
                        );
                        builders.put(executionId, builder);
                    } else {
                        builder.requireSameHeader(
                                rows.getString("activity_kind"),
                                rows.getObject("activity_id", UUID.class),
                                rows.getLong("state_version"),
                                rows.getTimestamp("lease_expires_at").toInstant(),
                                rows.getString("ruleset_id"),
                                rows.getInt("ruleset_version"),
                                rows.getInt("team_size")
                        );
                    }
                    builder.addParticipant(new LegacyParticipant(
                            rows.getInt("participant_index"),
                            rows.getString("side_key"),
                            rows.getObject("side_id", UUID.class),
                            rows.getObject("player_id", UUID.class),
                            rows.getObject("minecraft_uuid", UUID.class),
                            rows.getString("player_name")
                    ));
                }
            }
        }

        ArrayList<LegacyExecution> executions = new ArrayList<LegacyExecution>();
        for (ExecutionBuilder builder : builders.values()) {
            executions.add(builder.build());
        }
        return executions;
    }

    LegacyExecution heartbeatExecution(LegacyExecution execution, int requestedLeaseSeconds) throws SQLException {
        Objects.requireNonNull(execution, "execution");
        if (requestedLeaseSeconds < 1) {
            throw new IllegalArgumentException("requestedLeaseSeconds must be >= 1");
        }
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_heartbeat_execution(?, ?, ?)"
             )) {
            statement.setObject(1, execution.getExecutionId());
            statement.setLong(2, execution.getStateVersion());
            statement.setInt(3, requestedLeaseSeconds);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("competitive_runtime_heartbeat_execution returned no row");
                long nextStateVersion = row.getLong("state_version");
                Instant nextLease = row.getTimestamp("lease_expires_at").toInstant();
                return execution.withLease(nextStateVersion, nextLease);
            }
        }
    }

    UUID submitWinner(UUID executionId, UUID winnerSideId) throws SQLException {
        Objects.requireNonNull(winnerSideId, "winnerSideId");
        UUID operationId = deterministicReportOperation("WINNER", executionId, winnerSideId);
        return submitReport(operationId, executionId, "WINNER", winnerSideId);
    }

    UUID submitFailure(UUID executionId) throws SQLException {
        UUID operationId = deterministicReportOperation("FAILURE", executionId, null);
        return submitReport(operationId, executionId, "FAILURE", null);
    }

    private UUID submitReport(UUID operationId, UUID executionId, String kind, UUID winnerId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(executionId, "executionId");
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_submit_report(?, ?, ?, ?)"
             )) {
            statement.setObject(1, operationId);
            statement.setObject(2, executionId);
            statement.setString(3, kind);
            if (winnerId == null) statement.setNull(4, java.sql.Types.OTHER);
            else statement.setObject(4, winnerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("competitive_runtime_submit_report returned no row");
                return row.getObject(1, UUID.class);
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static UUID deterministicReportOperation(String kind, UUID executionId, UUID winnerId) {
        Objects.requireNonNull(executionId, "executionId");
        String value = "minecraft-server:legacy-runtime:report:" + kind + ":" + executionId
                + ":" + (winnerId == null ? "" : winnerId.toString());
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static final class ExecutionBuilder {
        private final UUID executionId;
        private final String activityKind;
        private final UUID activityId;
        private final long stateVersion;
        private final Instant leaseExpiresAt;
        private final String rulesetId;
        private final int rulesetVersion;
        private final int teamSize;
        private final List<LegacyParticipant> participants = new ArrayList<LegacyParticipant>();

        private ExecutionBuilder(
                UUID executionId,
                String activityKind,
                UUID activityId,
                long stateVersion,
                Instant leaseExpiresAt,
                String rulesetId,
                int rulesetVersion,
                int teamSize
        ) {
            this.executionId = executionId;
            this.activityKind = activityKind;
            this.activityId = activityId;
            this.stateVersion = stateVersion;
            this.leaseExpiresAt = leaseExpiresAt;
            this.rulesetId = rulesetId;
            this.rulesetVersion = rulesetVersion;
            this.teamSize = teamSize;
        }

        private void requireSameHeader(
                String nextActivityKind,
                UUID nextActivityId,
                long nextStateVersion,
                Instant nextLeaseExpiresAt,
                String nextRulesetId,
                int nextRulesetVersion,
                int nextTeamSize
        ) throws SQLException {
            if (!activityKind.equals(nextActivityKind)
                    || !activityId.equals(nextActivityId)
                    || stateVersion != nextStateVersion
                    || !leaseExpiresAt.equals(nextLeaseExpiresAt)
                    || !rulesetId.equals(nextRulesetId)
                    || rulesetVersion != nextRulesetVersion
                    || teamSize != nextTeamSize) {
                throw new SQLException("competitive runtime poll returned inconsistent rows for execution " + executionId);
            }
        }

        private void addParticipant(LegacyParticipant participant) {
            participants.add(participant);
        }

        private LegacyExecution build() {
            return new LegacyExecution(
                    executionId,
                    activityKind,
                    activityId,
                    stateVersion,
                    leaseExpiresAt,
                    rulesetId,
                    rulesetVersion,
                    teamSize,
                    participants
            );
        }
    }
}
