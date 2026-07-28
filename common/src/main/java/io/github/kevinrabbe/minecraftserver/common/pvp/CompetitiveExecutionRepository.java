package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable trust boundary between common/PostgreSQL authority and disposable 1.8.9 competitive runtimes.
 *
 * <p>The legacy runtime may hold only a leased execution and submit one tiny outcome report. It never receives a
 * persistent-value mutation API. Common authority later applies the report through Ranked/Clan-War repositories and
 * closes this execution exactly once.</p>
 */
public final class CompetitiveExecutionRepository {
    private static final int MAX_QUERY_LIMIT = 500;

    private final DataSource dataSource;
    private final Duration backendFreshness;
    private final Duration maxLease;
    private final Clock clock;

    public CompetitiveExecutionRepository(
            DataSource dataSource,
            Duration backendFreshness,
            Duration maxLease
    ) {
        this(dataSource, backendFreshness, maxLease, Clock.systemUTC());
    }

    public CompetitiveExecutionRepository(
            DataSource dataSource,
            Duration backendFreshness,
            Duration maxLease,
            Clock clock
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.backendFreshness = requirePositive(backendFreshness, "backendFreshness");
        this.maxLease = requirePositive(maxLease, "maxLease");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompetitiveExecutionSnapshot assign(
            UUID assignmentOperationId,
            CompetitiveActivityKind activityKind,
            UUID activityId,
            String backendId,
            Duration lease
    ) throws SQLException {
        Objects.requireNonNull(assignmentOperationId, "assignmentOperationId");
        Objects.requireNonNull(activityKind, "activityKind");
        Objects.requireNonNull(activityId, "activityId");
        String normalizedBackend = requireBackendId(backendId);
        Duration normalizedLease = requireLease(lease);
        Instant now = clock.instant();
        Instant leaseExpiresAt = safePlus(now, normalizedLease);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, assignmentOperationId);
                Optional<CompetitiveExecutionSnapshot> processed = findByAssignmentOperation(
                        connection,
                        assignmentOperationId,
                        false
                );
                if (processed.isPresent()) {
                    CompetitiveExecutionSnapshot previous = processed.orElseThrow();
                    requireSameAssignment(previous, activityKind, activityId, normalizedBackend, assignmentOperationId);
                    connection.commit();
                    return previous;
                }

                requireFreshOnlineBackend(connection, normalizedBackend, now);
                requireActivityReadyForAssignment(connection, activityKind, activityId);
                Optional<CompetitiveExecutionSnapshot> existing = findByActivity(
                        connection,
                        activityKind,
                        activityId,
                        true
                );
                if (existing.isPresent()) {
                    throw new CompetitiveExecutionException(
                            "Competitive activity already has an execution: " + activityKind + "/" + activityId
                    );
                }

                UUID executionId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO competitive_executions(
                            execution_id,
                            assignment_operation_id,
                            activity_kind,
                            activity_id,
                            backend_id,
                            status,
                            lease_expires_at,
                            state_version
                        ) VALUES (?, ?, ?, ?, ?, 'ASSIGNED', ?, 0)
                        """)) {
                    statement.setObject(1, executionId);
                    statement.setObject(2, assignmentOperationId);
                    statement.setString(3, activityKind.name());
                    statement.setObject(4, activityId);
                    statement.setString(5, normalizedBackend);
                    statement.setTimestamp(6, Timestamp.from(leaseExpiresAt));
                    statement.executeUpdate();
                }

                CompetitiveExecutionSnapshot result = requireExecution(connection, executionId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public Optional<CompetitiveExecutionSnapshot> load(UUID executionId) throws SQLException {
        Objects.requireNonNull(executionId, "executionId");
        try (Connection connection = dataSource.getConnection()) {
            return findExecution(connection, executionId, false);
        }
    }

    public Optional<CompetitiveResultReportSnapshot> loadReport(UUID reportId) throws SQLException {
        Objects.requireNonNull(reportId, "reportId");
        try (Connection connection = dataSource.getConnection()) {
            return findReport(connection, reportId, false);
        }
    }

    /**
     * Marks the already-started durable activity as owned by the live legacy runtime. Exact retry of the same
     * ASSIGNED-version activation returns the existing ACTIVE execution without extending it again.
     */
    public CompetitiveExecutionSnapshot markActive(
            UUID executionId,
            String backendId,
            long expectedStateVersion,
            Duration lease
    ) throws SQLException {
        Objects.requireNonNull(executionId, "executionId");
        String normalizedBackend = requireBackendId(backendId);
        requireStateVersion(expectedStateVersion);
        Duration normalizedLease = requireLease(lease);
        Instant now = clock.instant();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                CompetitiveExecutionSnapshot current = requireExecution(connection, executionId, true);
                requireBackend(current, normalizedBackend);
                if (current.status() == CompetitiveExecutionStatus.ACTIVE
                        && current.stateVersion() == expectedStateVersion + 1) {
                    connection.commit();
                    return current;
                }
                if (current.status() != CompetitiveExecutionStatus.ASSIGNED) {
                    throw new CompetitiveExecutionException("Competitive execution is not assignable to runtime: " + current.status());
                }
                requireVersion(current, expectedStateVersion);
                requireLiveLease(current, now);
                requireFreshOnlineBackend(connection, normalizedBackend, now);

                Instant nextExpiry = strictExtension(current.leaseExpiresAt(), safePlus(now, normalizedLease));
                long nextVersion = increment(current.stateVersion(), "competitive execution", executionId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE competitive_executions
                        SET status = 'ACTIVE',
                            lease_expires_at = ?,
                            state_version = ?,
                            activated_at = ?
                        WHERE execution_id = ?
                          AND status = 'ASSIGNED'
                          AND state_version = ?
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(nextExpiry));
                    statement.setLong(2, nextVersion);
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setObject(4, executionId);
                    statement.setLong(5, current.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new CompetitiveExecutionException("Competitive execution changed concurrently: " + executionId);
                    }
                }

                CompetitiveExecutionSnapshot result = requireExecution(connection, executionId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public CompetitiveExecutionSnapshot heartbeat(
            UUID executionId,
            String backendId,
            long expectedStateVersion,
            Duration lease
    ) throws SQLException {
        Objects.requireNonNull(executionId, "executionId");
        String normalizedBackend = requireBackendId(backendId);
        requireStateVersion(expectedStateVersion);
        Duration normalizedLease = requireLease(lease);
        Instant now = clock.instant();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                CompetitiveExecutionSnapshot current = requireExecution(connection, executionId, true);
                requireBackend(current, normalizedBackend);
                if (current.status() != CompetitiveExecutionStatus.ASSIGNED
                        && current.status() != CompetitiveExecutionStatus.ACTIVE) {
                    throw new CompetitiveExecutionException("Competitive execution is not live: " + current.status());
                }
                requireVersion(current, expectedStateVersion);
                requireLiveLease(current, now);
                requireFreshOnlineBackend(connection, normalizedBackend, now);

                Instant nextExpiry = strictExtension(current.leaseExpiresAt(), safePlus(now, normalizedLease));
                long nextVersion = increment(current.stateVersion(), "competitive execution", executionId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE competitive_executions
                        SET lease_expires_at = ?,
                            state_version = ?
                        WHERE execution_id = ?
                          AND status = ?
                          AND state_version = ?
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(nextExpiry));
                    statement.setLong(2, nextVersion);
                    statement.setObject(3, executionId);
                    statement.setString(4, current.status().name());
                    statement.setLong(5, current.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new CompetitiveExecutionException("Competitive execution changed concurrently: " + executionId);
                    }
                }

                CompetitiveExecutionSnapshot result = requireExecution(connection, executionId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public CompetitiveResultReportSnapshot submitWinnerReport(
            UUID reportOperationId,
            UUID executionId,
            String backendId,
            UUID winnerId
    ) throws SQLException {
        return submitReport(
                reportOperationId,
                executionId,
                backendId,
                CompetitiveReportKind.WINNER,
                Objects.requireNonNull(winnerId, "winnerId")
        );
    }

    public CompetitiveResultReportSnapshot submitFailureReport(
            UUID reportOperationId,
            UUID executionId,
            String backendId
    ) throws SQLException {
        return submitReport(reportOperationId, executionId, backendId, CompetitiveReportKind.FAILURE, null);
    }

    public List<CompetitiveResultReportSnapshot> listPendingReports(int limit) throws SQLException {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT *
                     FROM competitive_result_reports
                     WHERE status = 'PENDING'
                     ORDER BY submitted_at ASC, report_id ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<CompetitiveResultReportSnapshot> result = new ArrayList<>();
                while (rows.next()) result.add(readReport(rows));
                return List.copyOf(result);
            }
        }
    }

    /** Expired executions with no submitted outcome; a submitted report is settled instead of being failed by recovery. */
    public List<CompetitiveExecutionSnapshot> listExpiredExecutions(int limit) throws SQLException {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT e.*
                     FROM competitive_executions e
                     WHERE e.status IN ('ASSIGNED', 'ACTIVE')
                       AND e.lease_expires_at <= ?
                       AND NOT EXISTS (
                           SELECT 1 FROM competitive_result_reports r WHERE r.execution_id = e.execution_id
                       )
                     ORDER BY e.lease_expires_at ASC, e.execution_id ASC
                     LIMIT ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(clock.instant()));
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<CompetitiveExecutionSnapshot> result = new ArrayList<>();
                while (rows.next()) result.add(readExecution(rows));
                return List.copyOf(result);
            }
        }
    }

    /** Atomically marks trusted settlement applied to the report and closes its execution. */
    public CompetitiveResultReportSnapshot markReportApplied(UUID reportId, UUID settlementOperationId) throws SQLException {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(settlementOperationId, "settlementOperationId");
        Instant now = clock.instant();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                CompetitiveResultReportSnapshot report = requireReport(connection, reportId, true);
                if (report.status() == CompetitiveReportStatus.APPLIED) {
                    if (!settlementOperationId.equals(report.settlementOperationId())) {
                        throw new CompetitiveExecutionException("Competitive report already applied by another settlement: " + reportId);
                    }
                    connection.commit();
                    return report;
                }

                CompetitiveExecutionSnapshot execution = requireExecution(connection, report.executionId(), true);
                if (execution.status() != CompetitiveExecutionStatus.ACTIVE) {
                    throw new CompetitiveExecutionException(
                            "Pending competitive report does not own an ACTIVE execution: " + report.executionId()
                    );
                }
                long nextVersion = increment(execution.stateVersion(), "competitive execution", execution.executionId());
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE competitive_executions
                        SET status = 'CLOSED',
                            close_reason = 'SETTLED',
                            settlement_operation_id = ?,
                            state_version = ?,
                            closed_at = ?
                        WHERE execution_id = ?
                          AND status = 'ACTIVE'
                          AND state_version = ?
                        """)) {
                    statement.setObject(1, settlementOperationId);
                    statement.setLong(2, nextVersion);
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setObject(4, execution.executionId());
                    statement.setLong(5, execution.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new CompetitiveExecutionException("Competitive execution changed concurrently: " + execution.executionId());
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE competitive_result_reports
                        SET status = 'APPLIED',
                            settlement_operation_id = ?,
                            processed_at = ?
                        WHERE report_id = ? AND status = 'PENDING'
                        """)) {
                    statement.setObject(1, settlementOperationId);
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setObject(3, reportId);
                    if (statement.executeUpdate() != 1) {
                        throw new CompetitiveExecutionException("Competitive report changed concurrently: " + reportId);
                    }
                }

                CompetitiveResultReportSnapshot result = requireReport(connection, reportId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Closes an expired execution after trusted common recovery has terminally resolved the durable activity. */
    public CompetitiveExecutionSnapshot markExecutionFailed(UUID executionId, UUID settlementOperationId) throws SQLException {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(settlementOperationId, "settlementOperationId");
        Instant now = clock.instant();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                CompetitiveExecutionSnapshot current = requireExecution(connection, executionId, true);
                if (current.status() == CompetitiveExecutionStatus.CLOSED) {
                    if (current.closeReason() != CompetitiveExecutionCloseReason.FAILED
                            || !settlementOperationId.equals(current.settlementOperationId())) {
                        throw new CompetitiveExecutionException("Competitive execution already closed differently: " + executionId);
                    }
                    connection.commit();
                    return current;
                }
                if (current.leaseExpiresAt().isAfter(now)) {
                    throw new CompetitiveExecutionException("Competitive execution lease is still live: " + executionId);
                }
                if (findReportByExecution(connection, executionId, true).isPresent()) {
                    throw new CompetitiveExecutionException("Competitive execution has a submitted report and must be settled: " + executionId);
                }

                long nextVersion = increment(current.stateVersion(), "competitive execution", executionId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE competitive_executions
                        SET status = 'CLOSED',
                            close_reason = 'FAILED',
                            settlement_operation_id = ?,
                            state_version = ?,
                            closed_at = ?
                        WHERE execution_id = ?
                          AND status IN ('ASSIGNED', 'ACTIVE')
                          AND state_version = ?
                        """)) {
                    statement.setObject(1, settlementOperationId);
                    statement.setLong(2, nextVersion);
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setObject(4, executionId);
                    statement.setLong(5, current.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new CompetitiveExecutionException("Competitive execution changed concurrently: " + executionId);
                    }
                }

                CompetitiveExecutionSnapshot result = requireExecution(connection, executionId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private CompetitiveResultReportSnapshot submitReport(
            UUID reportOperationId,
            UUID executionId,
            String backendId,
            CompetitiveReportKind kind,
            UUID winnerId
    ) throws SQLException {
        Objects.requireNonNull(reportOperationId, "reportOperationId");
        Objects.requireNonNull(executionId, "executionId");
        String normalizedBackend = requireBackendId(backendId);
        Instant now = clock.instant();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, reportOperationId);
                Optional<CompetitiveResultReportSnapshot> processed = findByReportOperation(
                        connection,
                        reportOperationId,
                        false
                );
                if (processed.isPresent()) {
                    CompetitiveResultReportSnapshot previous = processed.orElseThrow();
                    requireSameReport(previous, executionId, normalizedBackend, kind, winnerId, reportOperationId);
                    connection.commit();
                    return previous;
                }

                CompetitiveExecutionSnapshot execution = requireExecution(connection, executionId, true);
                requireBackend(execution, normalizedBackend);
                if (execution.status() != CompetitiveExecutionStatus.ACTIVE) {
                    throw new CompetitiveExecutionException("Competitive execution is not reportable: " + execution.status());
                }
                requireLiveLease(execution, now);
                requireFreshOnlineBackend(connection, normalizedBackend, now);
                if (findReportByExecution(connection, executionId, true).isPresent()) {
                    throw new CompetitiveExecutionException("Competitive execution already has an outcome report: " + executionId);
                }

                UUID reportId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO competitive_result_reports(
                            report_id,
                            report_operation_id,
                            execution_id,
                            backend_id,
                            report_kind,
                            winner_id,
                            status
                        ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                        """)) {
                    statement.setObject(1, reportId);
                    statement.setObject(2, reportOperationId);
                    statement.setObject(3, executionId);
                    statement.setString(4, normalizedBackend);
                    statement.setString(5, kind.name());
                    if (winnerId == null) statement.setNull(6, java.sql.Types.OTHER);
                    else statement.setObject(6, winnerId);
                    statement.executeUpdate();
                }

                CompetitiveResultReportSnapshot result = requireReport(connection, reportId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private void requireFreshOnlineBackend(Connection connection, String backendId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status, last_heartbeat_at
                FROM backends
                WHERE backend_id = ?
                FOR SHARE
                """)) {
            statement.setString(1, backendId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new CompetitiveExecutionException("Unknown competitive backend: " + backendId);
                }
                if (!"ONLINE".equals(row.getString("status"))) {
                    throw new CompetitiveExecutionException("Competitive backend is not ONLINE: " + backendId);
                }
                Instant heartbeat = row.getTimestamp("last_heartbeat_at").toInstant();
                if (heartbeat.isBefore(now.minus(backendFreshness))) {
                    throw new CompetitiveExecutionException("Competitive backend heartbeat is stale: " + backendId);
                }
            }
        }
    }

    private static void requireActivityReadyForAssignment(
            Connection connection,
            CompetitiveActivityKind kind,
            UUID activityId
    ) throws SQLException {
        String sql = switch (kind) {
            case RANKED_ARENA -> "SELECT status FROM ranked_matches WHERE match_id = ? FOR UPDATE";
            case CLAN_WAR -> "SELECT status FROM clan_wars WHERE war_id = ? FOR UPDATE";
        };
        String expected = switch (kind) {
            case RANKED_ARENA -> "CREATED";
            case CLAN_WAR -> "ROSTER_LOCKED";
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, activityId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new CompetitiveExecutionException("Unknown competitive activity: " + kind + "/" + activityId);
                }
                String status = row.getString("status");
                if (!expected.equals(status)) {
                    throw new CompetitiveExecutionException(
                            "Competitive activity is not ready for assignment: " + kind + "/" + activityId + " status=" + status
                    );
                }
            }
        }
    }

    private static Optional<CompetitiveExecutionSnapshot> findExecution(
            Connection connection,
            UUID executionId,
            boolean forUpdate
    ) throws SQLException {
        String sql = "SELECT * FROM competitive_executions WHERE execution_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, executionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readExecution(row)) : Optional.empty();
            }
        }
    }

    private static Optional<CompetitiveExecutionSnapshot> findByAssignmentOperation(
            Connection connection,
            UUID operationId,
            boolean forUpdate
    ) throws SQLException {
        String sql = "SELECT * FROM competitive_executions WHERE assignment_operation_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readExecution(row)) : Optional.empty();
            }
        }
    }

    private static Optional<CompetitiveExecutionSnapshot> findByActivity(
            Connection connection,
            CompetitiveActivityKind kind,
            UUID activityId,
            boolean forUpdate
    ) throws SQLException {
        String sql = "SELECT * FROM competitive_executions WHERE activity_kind = ? AND activity_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kind.name());
            statement.setObject(2, activityId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readExecution(row)) : Optional.empty();
            }
        }
    }

    private static CompetitiveExecutionSnapshot requireExecution(
            Connection connection,
            UUID executionId,
            boolean forUpdate
    ) throws SQLException {
        return findExecution(connection, executionId, forUpdate).orElseThrow(
                () -> new CompetitiveExecutionException("Unknown competitive execution: " + executionId)
        );
    }

    private static Optional<CompetitiveResultReportSnapshot> findReport(
            Connection connection,
            UUID reportId,
            boolean forUpdate
    ) throws SQLException {
        String sql = "SELECT * FROM competitive_result_reports WHERE report_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, reportId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readReport(row)) : Optional.empty();
            }
        }
    }

    private static Optional<CompetitiveResultReportSnapshot> findByReportOperation(
            Connection connection,
            UUID operationId,
            boolean forUpdate
    ) throws SQLException {
        String sql = "SELECT * FROM competitive_result_reports WHERE report_operation_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readReport(row)) : Optional.empty();
            }
        }
    }

    private static Optional<CompetitiveResultReportSnapshot> findReportByExecution(
            Connection connection,
            UUID executionId,
            boolean forUpdate
    ) throws SQLException {
        String sql = "SELECT * FROM competitive_result_reports WHERE execution_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, executionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readReport(row)) : Optional.empty();
            }
        }
    }

    private static CompetitiveResultReportSnapshot requireReport(
            Connection connection,
            UUID reportId,
            boolean forUpdate
    ) throws SQLException {
        return findReport(connection, reportId, forUpdate).orElseThrow(
                () -> new CompetitiveExecutionException("Unknown competitive result report: " + reportId)
        );
    }

    private static CompetitiveExecutionSnapshot readExecution(ResultSet row) throws SQLException {
        Timestamp activatedAt = row.getTimestamp("activated_at");
        Timestamp closedAt = row.getTimestamp("closed_at");
        String closeReason = row.getString("close_reason");
        return new CompetitiveExecutionSnapshot(
                row.getObject("execution_id", UUID.class),
                row.getObject("assignment_operation_id", UUID.class),
                CompetitiveActivityKind.valueOf(row.getString("activity_kind")),
                row.getObject("activity_id", UUID.class),
                row.getString("backend_id"),
                CompetitiveExecutionStatus.valueOf(row.getString("status")),
                row.getTimestamp("lease_expires_at").toInstant(),
                row.getLong("state_version"),
                closeReason == null ? null : CompetitiveExecutionCloseReason.valueOf(closeReason),
                row.getObject("settlement_operation_id", UUID.class),
                row.getTimestamp("assigned_at").toInstant(),
                activatedAt == null ? null : activatedAt.toInstant(),
                closedAt == null ? null : closedAt.toInstant()
        );
    }

    private static CompetitiveResultReportSnapshot readReport(ResultSet row) throws SQLException {
        Timestamp processedAt = row.getTimestamp("processed_at");
        return new CompetitiveResultReportSnapshot(
                row.getObject("report_id", UUID.class),
                row.getObject("report_operation_id", UUID.class),
                row.getObject("execution_id", UUID.class),
                row.getString("backend_id"),
                CompetitiveReportKind.valueOf(row.getString("report_kind")),
                row.getObject("winner_id", UUID.class),
                CompetitiveReportStatus.valueOf(row.getString("status")),
                row.getObject("settlement_operation_id", UUID.class),
                row.getTimestamp("submitted_at").toInstant(),
                processedAt == null ? null : processedAt.toInstant()
        );
    }

    private static void requireSameAssignment(
            CompetitiveExecutionSnapshot previous,
            CompetitiveActivityKind kind,
            UUID activityId,
            String backendId,
            UUID operationId
    ) {
        if (previous.activityKind() != kind
                || !previous.activityId().equals(activityId)
                || !previous.backendId().equals(backendId)) {
            throw new CompetitiveExecutionException("Assignment operation_id reused for another request: " + operationId);
        }
    }

    private static void requireSameReport(
            CompetitiveResultReportSnapshot previous,
            UUID executionId,
            String backendId,
            CompetitiveReportKind kind,
            UUID winnerId,
            UUID operationId
    ) {
        if (!previous.executionId().equals(executionId)
                || !previous.backendId().equals(backendId)
                || previous.reportKind() != kind
                || !Objects.equals(previous.winnerId(), winnerId)) {
            throw new CompetitiveExecutionException("Report operation_id reused for another request: " + operationId);
        }
    }

    private static void requireBackend(CompetitiveExecutionSnapshot execution, String backendId) {
        if (!execution.backendId().equals(backendId)) {
            throw new CompetitiveExecutionException(
                    "Competitive execution belongs to backend " + execution.backendId() + ", not " + backendId
            );
        }
    }

    private static void requireVersion(CompetitiveExecutionSnapshot execution, long expected) {
        if (execution.stateVersion() != expected) {
            throw new CompetitiveExecutionException(
                    "Stale competitive execution state_version for " + execution.executionId()
                            + ": expected " + expected + " but was " + execution.stateVersion()
            );
        }
    }

    private static void requireLiveLease(CompetitiveExecutionSnapshot execution, Instant now) {
        if (!execution.leaseExpiresAt().isAfter(now)) {
            throw new CompetitiveExecutionException("Competitive execution lease expired: " + execution.executionId());
        }
    }

    private Duration requireLease(Duration lease) {
        Duration normalized = requirePositive(lease, "lease");
        if (normalized.compareTo(maxLease) > 0) {
            throw new IllegalArgumentException("lease exceeds configured safety ceiling " + maxLease);
        }
        return normalized;
    }

    private static Duration requirePositive(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be > 0");
        }
        return duration;
    }

    private static void requireStateVersion(long stateVersion) {
        if (stateVersion < 0) throw new IllegalArgumentException("expectedStateVersion must be >= 0");
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_QUERY_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_QUERY_LIMIT);
        }
    }

    private static String requireBackendId(String backendId) {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        return backendId.trim();
    }

    private static long increment(long current, String subject, Object id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new CompetitiveExecutionException(subject + " state_version overflow: " + id, exception);
        }
    }

    private static Instant safePlus(Instant instant, Duration duration) {
        try {
            return instant.plus(duration);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("lease expiry overflow", exception);
        }
    }

    private static Instant strictExtension(Instant current, Instant requested) {
        if (requested.isAfter(current)) return requested;
        return current.plusMillis(1);
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
