package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.Objects;
import java.util.UUID;

/** One row in the isolated 1.8.9 Ranked Arena ladder. */
public record RankedArenaLeaderboardEntry(
        int rank,
        UUID playerId,
        String playerName,
        int rating,
        long wins,
        long losses
) {
    public RankedArenaLeaderboardEntry {
        if (rank < 1 || rating < 0 || wins < 0 || losses < 0) {
            throw new IllegalArgumentException("rank/rating/wins/losses must be nonnegative and rank must be positive");
        }
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        playerName = playerName.trim();
    }
}
