package io.github.kevinrabbe.minecraftserver.legacy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private final UUID backendIncarnationId = UUID.randomUUID();
    private boolean backendRegistered;

    LegacyRuntimeDatabase(String jdbcUrl, String username, String password) {
        this.jdbcUrl = requireText(jdbcUrl, "jdbcUrl");
        this.username = requireText(username, "username");
        this.password = Objects.requireNonNull(password, "password");
    }

    void initializeDriver() throws ClassNotFoundException {
        DriverManager.setLoginTimeout(5);
        Class.forName("org.postgresql.Driver");
    }

    synchronized String heartbeatBackend(int playerCount) throws SQLException {
        if (playerCount < 0) {
            throw new IllegalArgumentException("playerCount must be >= 0");
        }
        String function = backendRegistered
                ? "competitive_runtime_heartbeat"
                : "competitive_runtime_register";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT " + function + "(?, ?)")) {
            statement.setObject(1, backendIncarnationId);
            statement.setInt(2, playerCount);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException(function + " returned no row");
                String backendId = row.getString(1);
                backendRegistered = true;
                return backendId;
            }
        }
    }

    synchronized String markOffline() throws SQLException {
        if (!backendRegistered) {
            throw new SQLException("competitive runtime backend was never registered");
        }
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_mark_offline(?)"
             )) {
            statement.setObject(1, backendIncarnationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("competitive_runtime_mark_offline returned no row");
                return row.getString(1);
            }
        }
    }

    List<LegacyExecution> pollActive(int executionLimit) throws SQLException {
        if (executionLimit < 1 || executionLimit > 64) {
            throw new IllegalArgumentException("executionLimit must be between 1 and 64");
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
                        builder = builderFrom(rows);
                        builders.put(executionId, builder);
                    } else {
                        requireSameHeader(builder, rows);
                    }
                    builder.addParticipant(participantFrom(rows));
                }
            }
        }

        ArrayList<LegacyExecution> executions = new ArrayList<LegacyExecution>();
        for (ExecutionBuilder builder : builders.values()) {
            executions.add(builder.build());
        }
        return executions;
    }

    /**
     * Exact admission lookup for one joining Minecraft identity. PostgreSQL scopes the query to this runtime
     * principal's backend and returns only the sanitized execution manifest.
     */
    LegacyExecution findPlayerExecution(UUID minecraftUuid) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_find_player_execution(?)"
             )) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet rows = statement.executeQuery()) {
                ExecutionBuilder builder = null;
                while (rows.next()) {
                    if (builder == null) {
                        builder = builderFrom(rows);
                    } else {
                        requireSameHeader(builder, rows);
                    }
                    builder.addParticipant(participantFrom(rows));
                }
                return builder == null ? null : builder.build();
            }
        }
    }

    /**
     * Reads one bounded keyset page from V71's execution-scoped loadout projection. The caller can continue paging
     * until an empty page is returned, so the transport bound never becomes an invented gameplay kit-size limit.
     */
    List<LegacyLoadoutItem> pageExecutionLoadout(
            UUID executionId,
            Integer afterParticipantIndex,
            Integer afterLoadoutItemIndex,
            int itemLimit
    ) throws SQLException {
        Objects.requireNonNull(executionId, "executionId");
        if (itemLimit < 1 || itemLimit > 500) {
            throw new IllegalArgumentException("itemLimit must be between 1 and 500");
        }
        if ((afterParticipantIndex == null) != (afterLoadoutItemIndex == null)) {
            throw new IllegalArgumentException("loadout cursor fields must both be null or both be present");
        }
        if (afterParticipantIndex != null && (afterParticipantIndex < 0 || afterLoadoutItemIndex < 0)) {
            throw new IllegalArgumentException("loadout cursor values must be >= 0");
        }

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_page_loadout(?, ?, ?, ?)"
             )) {
            statement.setObject(1, executionId);
            if (afterParticipantIndex == null) {
                statement.setNull(2, java.sql.Types.INTEGER);
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setInt(2, afterParticipantIndex);
                statement.setInt(3, afterLoadoutItemIndex);
            }
            statement.setInt(4, itemLimit);

            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<LegacyLoadoutItem> result = new ArrayList<LegacyLoadoutItem>();
                while (rows.next()) {
                    result.add(new LegacyLoadoutItem(
                            rows.getInt("participant_index"),
                            rows.getInt("loadout_item_index"),
                            rows.getString("definition_id"),
                            rows.getString("roll_state_json"),
                            rows.getInt("upgrade_level")
                    ));
                }
                return result;
            }
        }
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

    private static ExecutionBuilder builderFrom(ResultSet rows) throws SQLException {
        return new ExecutionBuilder(
                rows.getObject("execution_id", UUID.class),
                rows.getString("activity_kind"),
                rows.getObject("activity_id", UUID.class),
                rows.getLong("state_version"),
                rows.getTimestamp("lease_expires_at").toInstant(),
                rows.getString("ruleset_id"),
                rows.getInt("ruleset_version"),
                rows.getInt("team_size")
        );
    }

    private static void requireSameHeader(ExecutionBuilder builder, ResultSet rows) throws SQLException {
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

    private static LegacyParticipant participantFrom(ResultSet rows) throws SQLException {
        return new LegacyParticipant(
                rows.getInt("participant_index"),
                rows.getString("side_key"),
                rows.getObject("side_id", UUID.class),
                rows.getObject("player_id", UUID.class),
                rows.getObject("minecraft_uuid", UUID.class),
                rows.getString("player_name")
        );
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
                throw new SQLException("competitive runtime query returned inconsistent rows for execution " + executionId);
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
