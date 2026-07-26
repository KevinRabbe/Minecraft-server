package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence for one Map run's exact pinned transfer into its reserved encounter instance. */
public record MapEncounterHandoffSnapshot(
        UUID runId,
        UUID reservationId,
        UUID transferId,
        UUID playerId,
        UUID targetInstanceId,
        String targetBackendId,
        Instant createdAt
) {
    public MapEncounterHandoffSnapshot {
        runId = Objects.requireNonNull(runId, "runId");
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        transferId = Objects.requireNonNull(transferId, "transferId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        if (targetBackendId == null || targetBackendId.isBlank()) {
            throw new IllegalArgumentException("targetBackendId must not be blank");
        }
        targetBackendId = targetBackendId.trim();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
