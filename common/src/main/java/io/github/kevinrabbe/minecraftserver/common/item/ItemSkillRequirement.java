package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;

import java.util.Objects;

/** One type-level minimum skill requirement for using/equipping an item. Ownership and trade are unaffected. */
public record ItemSkillRequirement(
        SkillId skillId,
        int minimumLevel
) implements Comparable<ItemSkillRequirement> {
    public ItemSkillRequirement {
        skillId = Objects.requireNonNull(skillId, "skillId");
        if (minimumLevel < 1 || minimumLevel > SkillProgressionDefinition.LONG_TERM_MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "minimumLevel must be between 1 and "
                            + SkillProgressionDefinition.LONG_TERM_MAX_LEVEL
            );
        }
    }

    @Override
    public int compareTo(ItemSkillRequirement other) {
        return skillId.value().compareTo(Objects.requireNonNull(other, "other").skillId().value());
    }
}
