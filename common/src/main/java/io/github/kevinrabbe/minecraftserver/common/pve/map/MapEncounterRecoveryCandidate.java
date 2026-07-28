package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.util.Objects;
import java.util.UUID;

/** Bounded read projection describing one persisted Map encounter that must fail/release rather than continue. */
public record MapEncounterRecoveryCandidate(
        UUID runId,
        UUID reservationId,
        UUID playerId,
        long runStateVersion,
        UUID transferId,
        UUID sessionId,
        String sourceBackendId,
        MapEncounterRecoveryReason reason
) {
    public MapEncounterRecoveryCandidate {
        runId = Objects.requireNonNull(runId, "runId");
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        reason = Objects.requireNonNull(reason, "reason");
        if (runStateVersion < 0) {
            throw new IllegalArgumentException("runStateVersion must be >= 0");
        }
        if (reason == MapEncounterRecoveryReason.NO_HANDOFF) {
            if (transferId != null || sessionId != null || sourceBackendId != null) {
                throw new IllegalArgumentException("NO_HANDOFF candidate must not carry transfer state");
            }
        } else if (transferId == null || sessionId == null || sourceBackendId == null || sourceBackendId.isBlank()) {
            throw new IllegalArgumentException("transfer recovery candidate requires transfer/session/source backend");
        } else {
            sourceBackendId = sourceBackendId.trim();
        }
    }
}
