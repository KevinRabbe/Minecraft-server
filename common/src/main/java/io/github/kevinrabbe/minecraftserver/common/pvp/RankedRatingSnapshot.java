package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RankedRatingSnapshot(
        UUID playerId,
        int rating,
        long stateVersion,
        Instant updatedAt
) {
    public RankedRatingSnapshot {
        playerId = Objects.requireNonNull(playerId, "playerId");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (rating < 0 || stateVersion < 0) {
            throw new IllegalArgumentException("rating/stateVersion must be >= 0");
        }
    }
}
