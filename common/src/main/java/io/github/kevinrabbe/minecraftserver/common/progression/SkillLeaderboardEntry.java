package io.github.kevinrabbe.minecraftserver.common.progression;

import java.util.Objects;
import java.util.UUID;

/** One ranked row derived from authoritative skill experience and the current staged cap. */
public record SkillLeaderboardEntry(
        int rank,
        UUID playerId,
        String playerName,
        SkillId skillId,
        long experience,
        int level,
        int activeCap
) {
    public SkillLeaderboardEntry {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be >= 1");
        }
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (playerName == null || playerName.isBlank() || playerName.length() > 16) {
            throw new IllegalArgumentException("playerName must be a valid Minecraft name projection");
        }
        playerName = playerName.trim();
        skillId = Objects.requireNonNull(skillId, "skillId");
        if (experience < 0) {
            throw new IllegalArgumentException("experience must be >= 0");
        }
        if (level < 0 || level > activeCap) {
            throw new IllegalArgumentException("level must be between 0 and activeCap");
        }
        SkillCapStage.fromActiveCap(activeCap);
    }
}
