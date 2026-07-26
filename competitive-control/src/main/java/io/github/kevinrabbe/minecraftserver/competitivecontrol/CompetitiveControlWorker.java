package io.github.kevinrabbe.minecraftserver.competitivecontrol;

import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveDispatchCandidate;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveDispatchRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveDispatchService;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionService;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveResultReportSnapshot;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Trusted worker that isolates every settlement/recovery/dispatch candidate so one bad row cannot poison the batch. */
public final class CompetitiveControlWorker {
    private final CompetitiveExecutionRepository executions;
    private final CompetitiveExecutionService executionService;
    private final CompetitiveDispatchRepository dispatchRepository;
    private final CompetitiveDispatchService dispatchService;
    private final int batchLimit;
    private final Logger logger;

    public CompetitiveControlWorker(
            CompetitiveExecutionRepository executions,
            CompetitiveExecutionService executionService,
            CompetitiveDispatchRepository dispatchRepository,
            CompetitiveDispatchService dispatchService,
            int batchLimit,
            Logger logger
    ) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.executionService = Objects.requireNonNull(executionService, "executionService");
        this.dispatchRepository = Objects.requireNonNull(dispatchRepository, "dispatchRepository");
        this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService");
        if (batchLimit < 1 || batchLimit > 500) {
            throw new IllegalArgumentException("batchLimit must be between 1 and 500");
        }
        this.batchLimit = batchLimit;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompetitiveControlPassResult runOnce() throws SQLException {
        List<CompetitiveResultReportSnapshot> pendingReports = executions.listPendingReports(batchLimit);
        int reportsApplied = 0;
        int reportFailures = 0;
        for (CompetitiveResultReportSnapshot report : pendingReports) {
            try {
                executionService.processReport(report.reportId());
                reportsApplied++;
            } catch (SQLException | RuntimeException exception) {
                reportFailures++;
                logger.log(
                        Level.WARNING,
                        "Competitive report settlement failed for report " + report.reportId()
                                + " / execution " + report.executionId(),
                        exception
                );
            }
        }

        List<CompetitiveExecutionSnapshot> expiredExecutions = executions.listExpiredExecutions(batchLimit);
        int executionsRecovered = 0;
        int recoveryFailures = 0;
        for (CompetitiveExecutionSnapshot execution : expiredExecutions) {
            try {
                executionService.recoverExpiredExecution(execution.executionId());
                executionsRecovered++;
            } catch (SQLException | RuntimeException exception) {
                recoveryFailures++;
                logger.log(
                        Level.WARNING,
                        "Competitive execution recovery failed for " + execution.executionId()
                                + " / activity " + execution.activityKind() + "/" + execution.activityId(),
                        exception
                );
            }
        }

        // Settlement/recovery happens first so capacity released in this pass is immediately available for new work.
        List<CompetitiveDispatchCandidate> readyActivities = dispatchRepository.listReadyActivities(batchLimit);
        int executionsDispatched = 0;
        int dispatchDeferred = 0;
        int dispatchFailures = 0;
        for (CompetitiveDispatchCandidate candidate : readyActivities) {
            try {
                if (dispatchService.dispatchCandidate(candidate).isPresent()) {
                    executionsDispatched++;
                } else {
                    dispatchDeferred++;
                }
            } catch (SQLException | RuntimeException exception) {
                dispatchFailures++;
                logger.log(
                        Level.WARNING,
                        "Competitive dispatch failed for " + candidate.activityKind() + "/" + candidate.activityId(),
                        exception
                );
            }
        }

        return new CompetitiveControlPassResult(
                pendingReports.size(),
                reportsApplied,
                reportFailures,
                expiredExecutions.size(),
                executionsRecovered,
                recoveryFailures,
                readyActivities.size(),
                executionsDispatched,
                dispatchDeferred,
                dispatchFailures
        );
    }
}
