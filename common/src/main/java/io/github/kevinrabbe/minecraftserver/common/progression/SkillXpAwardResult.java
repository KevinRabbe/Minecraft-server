package io.github.kevinrabbe.minecraftserver.common.progression;

import java.util.Objects;
import java.util.UUID;

public record SkillXpAwardResult(
        UUID playerId,
        SkillId skillId,
        long requestedExperience,
        long grantedExperience,
        long previousExperience,
        long newExperience,
        int previousLevel,
        int newLevel,
        int activeCap,
        long stateVersion,
        String reason
) {
    public SkillXpAwardResult {
        playerId = Objects.requireNonNull(playerId, "playerId");
        skillId = Objects.requireNonNull(skillId, "skillId");
        if (requestedExperience <= 0
                || grantedExperience < 0
                || grantedExperience > requestedExperience
                || previousExperience < 0
                || newExperience < previousExperience
                || newExperience - previousExperience != grantedExperience
                || previousLevel < 0
                || newLevel < previousLevel
                || stateVersion < 0) {
            throw new IllegalArgumentException("invalid skill XP award result");
        }
        SkillCapStage.fromActiveCap(activeCap);
        if (newLevel > activeCap) {
            throw new IllegalArgumentException("newLevel must not exceed active cap");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
    }
}
