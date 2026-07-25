package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;

import java.util.Objects;
import java.util.regex.Pattern;

/** XP policy attached to one immutable craft recipe version. */
public record CraftingExperienceDefinition(
        String recipeId,
        int recipeVersion,
        SkillId skillId,
        long requestedExperience
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public CraftingExperienceDefinition {
        if (recipeId == null || recipeId.isBlank()) {
            throw new IllegalArgumentException("recipeId must not be blank");
        }
        recipeId = recipeId.trim();
        if (!ID.matcher(recipeId).matches()) {
            throw new IllegalArgumentException("recipeId has invalid format: " + recipeId);
        }
        if (recipeVersion < 0) {
            throw new IllegalArgumentException("recipeVersion must be >= 0");
        }
        skillId = Objects.requireNonNull(skillId, "skillId");
        if (requestedExperience <= 0) {
            throw new IllegalArgumentException("requestedExperience must be > 0");
        }
    }
}
