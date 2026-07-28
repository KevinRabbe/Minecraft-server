package io.github.kevinrabbe.minecraftserver.common.artifact;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current profile choice plus points derived from immutable artifact discoveries. */
public record AttunementSnapshot(
        UUID playerId,
        String activeProfileId,
        long totalPoints,
        long stateVersion,
        Instant updatedAt
) {
    public AttunementSnapshot {
        playerId = Objects.requireNonNull(playerId, "playerId");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (totalPoints < 0 || stateVersion < 0) {
            throw new IllegalArgumentException("totalPoints/stateVersion must be >= 0");
        }
    }
}
