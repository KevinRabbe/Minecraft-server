package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Ready durable activity awaiting assignment to an isolated legacy competitive backend. */
public record CompetitiveDispatchCandidate(
        CompetitiveActivityKind activityKind,
        UUID activityId,
        Instant readySince
) {
    public CompetitiveDispatchCandidate {
        activityKind = Objects.requireNonNull(activityKind, "activityKind");
        activityId = Objects.requireNonNull(activityId, "activityId");
        readySince = Objects.requireNonNull(readySince, "readySince");
    }
}
