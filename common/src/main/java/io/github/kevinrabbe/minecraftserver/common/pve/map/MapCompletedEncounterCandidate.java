package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.util.Objects;
import java.util.UUID;

/** One completed Map whose durable rewards/release still need idempotent recovery. */
public record MapCompletedEncounterCandidate(
        UUID runId,
        UUID reservationId,
        long runStateVersion
) {
    public MapCompletedEncounterCandidate {
        runId = Objects.requireNonNull(runId, "runId");
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        if (runStateVersion < 0) {
            throw new IllegalArgumentException("runStateVersion must be >= 0");
        }
    }
}
