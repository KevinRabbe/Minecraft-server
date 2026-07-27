package io.github.kevinrabbe.minecraftserver.common.analytics;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;

/** Read-only cohort-retention result derived exclusively from authoritative network-session starts. */
public record SessionRetentionSummary(
        Instant cohortStart,
        Instant cohortEnd,
        Instant returnWindowStart,
        Instant returnWindowEnd,
        Instant observedReturnThrough,
        long cohortPlayers,
        long returnedPlayers
) {
    public SessionRetentionSummary {
        cohortStart = Objects.requireNonNull(cohortStart, "cohortStart");
        cohortEnd = Objects.requireNonNull(cohortEnd, "cohortEnd");
        returnWindowStart = Objects.requireNonNull(returnWindowStart, "returnWindowStart");
        returnWindowEnd = Objects.requireNonNull(returnWindowEnd, "returnWindowEnd");
        observedReturnThrough = Objects.requireNonNull(observedReturnThrough, "observedReturnThrough");
        if (!cohortEnd.isAfter(cohortStart)) {
            throw new IllegalArgumentException("cohortEnd must be after cohortStart");
        }
        if (returnWindowStart.isBefore(cohortEnd)) {
            throw new IllegalArgumentException("returnWindowStart must not overlap the cohort window");
        }
        if (!returnWindowEnd.isAfter(returnWindowStart)) {
            throw new IllegalArgumentException("returnWindowEnd must be after returnWindowStart");
        }
        if (observedReturnThrough.isBefore(returnWindowStart) || observedReturnThrough.isAfter(returnWindowEnd)) {
            throw new IllegalArgumentException("observedReturnThrough must be inside the return window");
        }
        if (cohortPlayers < 0 || returnedPlayers < 0 || returnedPlayers > cohortPlayers) {
            throw new IllegalArgumentException("invalid cohort retention counts");
        }
    }

    public OptionalDouble retentionRate() {
        if (cohortPlayers == 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((double) returnedPlayers / (double) cohortPlayers);
    }
}
