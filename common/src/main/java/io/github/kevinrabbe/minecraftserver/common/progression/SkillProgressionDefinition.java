package io.github.kevinrabbe.minecraftserver.common.progression;

import java.util.List;
import java.util.Objects;

/** Immutable cumulative XP thresholds for one skill through the long-term level-100 ceiling. */
public record SkillProgressionDefinition(
        SkillId skillId,
        List<Long> cumulativeExperienceByLevel
) {
    public static final int LONG_TERM_MAX_LEVEL = 100;

    public SkillProgressionDefinition {
        skillId = Objects.requireNonNull(skillId, "skillId");
        cumulativeExperienceByLevel = List.copyOf(
                Objects.requireNonNull(cumulativeExperienceByLevel, "cumulativeExperienceByLevel")
        );
        if (cumulativeExperienceByLevel.size() != LONG_TERM_MAX_LEVEL + 1) {
            throw new IllegalArgumentException(
                    "cumulativeExperienceByLevel must contain levels 0..100 exactly"
            );
        }
        if (cumulativeExperienceByLevel.getFirst() != 0L) {
            throw new IllegalArgumentException("level 0 cumulative experience must be 0");
        }
        long previous = -1L;
        for (int level = 0; level <= LONG_TERM_MAX_LEVEL; level++) {
            Long threshold = cumulativeExperienceByLevel.get(level);
            if (threshold == null || threshold < 0) {
                throw new IllegalArgumentException("experience threshold must be nonnegative at level " + level);
            }
            if (level > 0 && threshold <= previous) {
                throw new IllegalArgumentException("experience thresholds must strictly increase by level");
            }
            previous = threshold;
        }
    }

    public long experienceForLevel(int level) {
        if (level < 0 || level > LONG_TERM_MAX_LEVEL) {
            throw new IllegalArgumentException("level must be between 0 and 100");
        }
        return cumulativeExperienceByLevel.get(level);
    }

    public int levelForExperience(long experience, int activeCap) {
        if (experience < 0) {
            throw new IllegalArgumentException("experience must be >= 0");
        }
        SkillCapStage.fromActiveCap(activeCap);
        int high = Math.min(activeCap, LONG_TERM_MAX_LEVEL);
        int low = 0;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (experienceForLevel(mid) <= experience) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }
}
