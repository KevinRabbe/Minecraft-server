package io.github.kevinrabbe.minecraftserver.competitivecontrol;

import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionService;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveResultReportSnapshot;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Trusted worker that isolates every settlement/recovery candidate so one bad row cannot poison the batch. */
public final class CompetitiveControlWorker {
    private final CompetitiveExecutionRepository executions;
    private final CompetitiveExecutionService service;
    private final int batchLimit;
    private final Logger logger;

    public CompetitiveControlWorker(
            CompetitiveExecutionRepository executions,
            CompetitiveExecutionService service,
            int batchLimit,
            Logger logger
    ) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.service = Objects.requireNonNull(service, "service");
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
                service.processReport(report.reportId());
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
                service.recoverExpiredExecution(execution.executionId());
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

        return new CompetitiveControlPassResult(
                pendingReports.size(),
                reportsApplied,
                reportFailures,
                expiredExecutions.size(),
                executionsRecovered,
                recoveryFailures
        );
    }
}
