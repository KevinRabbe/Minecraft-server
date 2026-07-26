package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.util.Objects;
import java.util.UUID;

/** Stable participant identity plus the current display-name projection for one Map leaderboard row. */
public record MapLeaderboardParticipant(UUID playerId, String playerName) {
    public MapLeaderboardParticipant {
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        playerName = playerName.trim();
    }
}
