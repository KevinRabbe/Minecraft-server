package io.github.kevinrabbe.minecraftserver.common.pvp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted dispatcher for ready Ranked/Clan-War activities. Legacy runtimes never call this authority. */
public final class CompetitiveDispatchRepository {
    private static final int MAX_QUERY_LIMIT = 500;

    private final DataSource dataSource;
    private final CompetitiveExecutionRepository executions;
    private final int backendFreshnessSeconds;
    private final int assignmentLeaseSeconds;

    public CompetitiveDispatchRepository(
            DataSource dataSource,
            CompetitiveExecutionRepository executions,
            Duration backendFreshness,
            Duration assignmentLease
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.backendFreshnessSeconds = wholeSeconds(backendFreshness, "backendFreshness");
        this.assignmentLeaseSeconds = wholeSeconds(assignmentLease, "assignmentLease");
    }

    public List<CompetitiveDispatchCandidate> listReadyActivities(int limit) throws SQLException {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT ready.activity_kind, ready.activity_id, ready.ready_since
                     FROM (
                         SELECT 'RANKED_ARENA'::TEXT AS activity_kind,
                                match_id AS activity_id,
                                created_at AS ready_since
                         FROM ranked_matches
                         WHERE status = 'CREATED'
                         UNION ALL
                         SELECT 'CLAN_WAR'::TEXT AS activity_kind,
                                war_id AS activity_id,
                                created_at AS ready_since
                         FROM clan_wars
                         WHERE status = 'ROSTER_LOCKED'
                     ) ready
                     WHERE NOT EXISTS (
                         SELECT 1
                         FROM competitive_executions e
                         WHERE e.activity_kind = ready.activity_kind
                           AND e.activity_id = ready.activity_id
                     )
                     ORDER BY ready.ready_since ASC, ready.activity_kind ASC, ready.activity_id ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<CompetitiveDispatchCandidate> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new CompetitiveDispatchCandidate(
                            CompetitiveActivityKind.valueOf(rows.getString("activity_kind")),
                            rows.getObject("activity_id", UUID.class),
                            rows.getTimestamp("ready_since").toInstant()
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    /**
     * Atomically selects one healthy allowlisted backend with spare capacity and creates its ASSIGNED execution.
     * Empty means no eligible backend currently has capacity; the durable activity remains ready for a later pass.
     */
    public Optional<CompetitiveExecutionSnapshot> dispatch(
            UUID assignmentOperationId,
            CompetitiveDispatchCandidate candidate
    ) throws SQLException {
        Objects.requireNonNull(assignmentOperationId, "assignmentOperationId");
        Objects.requireNonNull(candidate, "candidate");

        UUID executionId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT competitive_dispatch_execution(?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, assignmentOperationId);
            statement.setString(2, candidate.activityKind().name());
            statement.setObject(3, candidate.activityId());
            statement.setInt(4, backendFreshnessSeconds);
            statement.setInt(5, assignmentLeaseSeconds);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("competitive_dispatch_execution returned no result row");
                }
                executionId = row.getObject(1, UUID.class);
            }
        }

        if (executionId == null) return Optional.empty();
        return Optional.of(executions.load(executionId).orElseThrow(
                () -> new SQLException("Competitive dispatch committed but execution is missing: " + executionId)
        ));
    }

    private static int wholeSeconds(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be > 0");
        }
        if (duration.getNano() != 0) {
            throw new IllegalArgumentException(field + " must use whole seconds");
        }
        long seconds;
        try {
            seconds = duration.toSeconds();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " is too large", exception);
        }
        if (seconds < 1 || seconds > 3_600) {
            throw new IllegalArgumentException(field + " must be between 1 and 3600 seconds");
        }
        return Math.toIntExact(seconds);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_QUERY_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_QUERY_LIMIT);
        }
    }
}
