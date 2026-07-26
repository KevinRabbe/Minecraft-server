package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted common-side orchestration for disposable 1.8.9 execution.
 *
 * <p>The legacy runtime never calls rating/custody authorities. It receives an execution, then submits only WINNER or
 * FAILURE. This service starts the durable activity, applies the bounded report through existing exactly-once
 * authorities, and recovers expired executions using deterministic operation IDs.</p>
 */
public final class CompetitiveExecutionService {
    private final CompetitiveExecutionRepository executions;
    private final RankedArenaRepository ranked;
    private final ClanWarLifecycleRepository clanWars;
    private final ClanWarResolutionRepository clanWarResolutions;
    private final Clock clock;

    public CompetitiveExecutionService(
            CompetitiveExecutionRepository executions,
            RankedArenaRepository ranked,
            ClanWarLifecycleRepository clanWars,
            ClanWarResolutionRepository clanWarResolutions
    ) {
        this(executions, ranked, clanWars, clanWarResolutions, Clock.systemUTC());
    }

    public CompetitiveExecutionService(
            CompetitiveExecutionRepository executions,
            RankedArenaRepository ranked,
            ClanWarLifecycleRepository clanWars,
            ClanWarResolutionRepository clanWarResolutions,
            Clock clock
    ) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.ranked = Objects.requireNonNull(ranked, "ranked");
        this.clanWars = Objects.requireNonNull(clanWars, "clanWars");
        this.clanWarResolutions = Objects.requireNonNull(clanWarResolutions, "clanWarResolutions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Starts the durable match/war first, then marks the runtime execution ACTIVE.
     *
     * <p>If the process dies after the durable start but before markActive, the deterministic start operation replays
     * and expired-execution recovery can still cancel/fail the now-active activity safely.</p>
     */
    public CompetitiveExecutionSnapshot activate(
            UUID executionId,
            String backendId,
            Duration lease
    ) throws SQLException {
        Objects.requireNonNull(executionId, "executionId");
        CompetitiveExecutionSnapshot execution = executions.load(executionId).orElseThrow(
                () -> new CompetitiveExecutionException("Unknown competitive execution: " + executionId)
        );
        requireBackend(execution, backendId);
        if (execution.status() == CompetitiveExecutionStatus.ACTIVE) {
            return execution;
        }
        if (execution.status() != CompetitiveExecutionStatus.ASSIGNED) {
            throw new CompetitiveExecutionException("Competitive execution cannot activate from " + execution.status());
        }
        if (!execution.leaseExpiresAt().isAfter(clock.instant())) {
            throw new CompetitiveExecutionException("Competitive execution lease expired before activation: " + executionId);
        }

        UUID startOperationId = operationId("start", executionId);
        switch (execution.activityKind()) {
            case RANKED_ARENA -> ranked.startMatch(startOperationId, execution.activityId());
            case CLAN_WAR -> clanWars.start(startOperationId, execution.activityId());
        }
        return executions.markActive(
                execution.executionId(),
                backendId,
                execution.stateVersion(),
                lease
        );
    }

    /** Applies one already-validated runtime report through the existing persistent authority exactly once. */
    public CompetitiveResultReportSnapshot processReport(UUID reportId) throws SQLException {
        Objects.requireNonNull(reportId, "reportId");
        CompetitiveResultReportSnapshot report = executions.loadReport(reportId).orElseThrow(
                () -> new CompetitiveExecutionException("Unknown competitive result report: " + reportId)
        );
        if (report.status() == CompetitiveReportStatus.APPLIED) {
            return report;
        }
        CompetitiveExecutionSnapshot execution = executions.load(report.executionId()).orElseThrow(
                () -> new CompetitiveExecutionException("Missing execution for competitive report: " + reportId)
        );

        UUID settlementOperationId = operationId("report-settlement", reportId);
        switch (report.reportKind()) {
            case WINNER -> applyWinner(execution, report, settlementOperationId);
            case FAILURE -> applyFailure(execution, settlementOperationId);
        }
        return executions.markReportApplied(reportId, settlementOperationId);
    }

    /** Bounded worker helper for trusted common-side report settlement. */
    public int processPending(int limit) throws SQLException {
        List<CompetitiveResultReportSnapshot> pending = executions.listPendingReports(limit);
        for (CompetitiveResultReportSnapshot report : pending) {
            processReport(report.reportId());
        }
        return pending.size();
    }

    /**
     * Terminally resolves expired executions that never produced a report.
     * Submitted reports are intentionally excluded by the repository and remain eligible for normal settlement.
     */
    public int recoverExpired(int limit) throws SQLException {
        List<CompetitiveExecutionSnapshot> expired = executions.listExpiredExecutions(limit);
        for (CompetitiveExecutionSnapshot execution : expired) {
            UUID failureOperationId = operationId("expired-failure", execution.executionId());
            switch (execution.activityKind()) {
                case RANKED_ARENA -> ranked.cancelMatch(failureOperationId, execution.activityId());
                case CLAN_WAR -> clanWarResolutions.fail(failureOperationId, execution.activityId());
            }
            executions.markExecutionFailed(execution.executionId(), failureOperationId);
        }
        return expired.size();
    }

    private void applyWinner(
            CompetitiveExecutionSnapshot execution,
            CompetitiveResultReportSnapshot report,
            UUID settlementOperationId
    ) throws SQLException {
        UUID winnerId = Objects.requireNonNull(report.winnerId(), "winnerId");
        switch (execution.activityKind()) {
            case RANKED_ARENA -> ranked.completeMatch(settlementOperationId, execution.activityId(), winnerId);
            case CLAN_WAR -> clanWarResolutions.complete(settlementOperationId, execution.activityId(), winnerId);
        }
    }

    private void applyFailure(CompetitiveExecutionSnapshot execution, UUID settlementOperationId) throws SQLException {
        switch (execution.activityKind()) {
            case RANKED_ARENA -> ranked.cancelMatch(settlementOperationId, execution.activityId());
            case CLAN_WAR -> clanWarResolutions.fail(settlementOperationId, execution.activityId());
        }
    }

    private static void requireBackend(CompetitiveExecutionSnapshot execution, String backendId) {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        if (!execution.backendId().equals(backendId.trim())) {
            throw new CompetitiveExecutionException(
                    "Competitive execution belongs to backend " + execution.backendId() + ", not " + backendId.trim()
            );
        }
    }

    private static UUID operationId(String action, UUID sourceId) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:competitive:" + action + ":" + sourceId).getBytes(StandardCharsets.UTF_8)
        );
    }
}
