package io.github.kevinrabbe.minecraftserver.common.progression;

import java.util.Objects;
import java.util.UUID;

public record SkillProgressSnapshot(
        UUID playerId,
        SkillId skillId,
        long experience,
        int level,
        int activeCap,
        long stateVersion
) {
    public SkillProgressSnapshot {
        playerId = Objects.requireNonNull(playerId, "playerId");
        skillId = Objects.requireNonNull(skillId, "skillId");
        if (experience < 0 || level < 0 || stateVersion < 0) {
            throw new IllegalArgumentException("skill progress values must be nonnegative");
        }
        SkillCapStage.fromActiveCap(activeCap);
        if (level > activeCap) {
            throw new IllegalArgumentException("level must not exceed active cap");
        }
    }
}
