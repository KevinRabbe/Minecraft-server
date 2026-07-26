package io.github.kevinrabbe.minecraftserver.competitivecontrol;

/** One bounded trusted settlement/recovery/preparation/dispatch pass. */
public record CompetitiveControlPassResult(
        int pendingReportsSeen,
        int reportsApplied,
        int reportFailures,
        int expiredExecutionsSeen,
        int executionsRecovered,
        int recoveryFailures,
        int rosterLockCandidatesSeen,
        int clanWarRostersLocked,
        int rosterLockFailures,
        int readyActivitiesSeen,
        int executionsDispatched,
        int dispatchDeferred,
        int dispatchFailures
) {
    public CompetitiveControlPassResult {
        if (pendingReportsSeen < 0
                || reportsApplied < 0
                || reportFailures < 0
                || expiredExecutionsSeen < 0
                || executionsRecovered < 0
                || recoveryFailures < 0
                || rosterLockCandidatesSeen < 0
                || clanWarRostersLocked < 0
                || rosterLockFailures < 0
                || readyActivitiesSeen < 0
                || executionsDispatched < 0
                || dispatchDeferred < 0
                || dispatchFailures < 0) {
            throw new IllegalArgumentException("competitive control pass counts must be nonnegative");
        }
        if (reportsApplied + reportFailures != pendingReportsSeen) {
            throw new IllegalArgumentException("report outcome counts must equal pendingReportsSeen");
        }
        if (executionsRecovered + recoveryFailures != expiredExecutionsSeen) {
            throw new IllegalArgumentException("recovery outcome counts must equal expiredExecutionsSeen");
        }
        if (clanWarRostersLocked + rosterLockFailures != rosterLockCandidatesSeen) {
            throw new IllegalArgumentException("roster-lock outcome counts must equal rosterLockCandidatesSeen");
        }
        if (executionsDispatched + dispatchDeferred + dispatchFailures != readyActivitiesSeen) {
            throw new IllegalArgumentException("dispatch outcome counts must equal readyActivitiesSeen");
        }
    }

    public int failures() {
        return reportFailures + recoveryFailures + rosterLockFailures + dispatchFailures;
    }

    public int transitions() {
        return reportsApplied + executionsRecovered + clanWarRostersLocked + executionsDispatched;
    }
}
