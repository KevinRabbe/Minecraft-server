package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.util.Objects;
import java.util.UUID;

/** One committed Map-open result including the fenced player-state version advanced by the same transaction. */
public record MapPlayerStateOpenResult(
        UUID runId,
        UUID playerId,
        long playerStateVersion,
        long destroyedItemStateVersion
) {
    public MapPlayerStateOpenResult {
        runId = Objects.requireNonNull(runId, "runId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (playerStateVersion < 0 || destroyedItemStateVersion < 0) {
            throw new IllegalArgumentException("state versions must be >= 0");
        }
    }
}
