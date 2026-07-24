package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable type-level crafting recipe. Exact quantities/requirements are content data. */
public record CraftRecipeDefinition(
        String recipeId,
        List<RecipeIngredient> ingredients,
        String outputDefinitionId,
        int outputQuantity,
        SkillId requiredSkillId,
        int requiredSkillLevel
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public CraftRecipeDefinition {
        recipeId = requireId(recipeId, "recipeId");
        outputDefinitionId = requireId(outputDefinitionId, "outputDefinitionId");
        ingredients = List.copyOf(Objects.requireNonNull(ingredients, "ingredients"));
        if (ingredients.isEmpty()) {
            throw new IllegalArgumentException("ingredients must not be empty");
        }
        if (outputQuantity <= 0) {
            throw new IllegalArgumentException("outputQuantity must be > 0");
        }
        if (requiredSkillLevel < 0) {
            throw new IllegalArgumentException("requiredSkillLevel must be >= 0");
        }
        if ((requiredSkillId == null) != (requiredSkillLevel == 0)) {
            throw new IllegalArgumentException(
                    "requiredSkillId must be null exactly when requiredSkillLevel is 0"
            );
        }
    }

    private static String requireId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " has invalid format: " + normalized);
        }
        return normalized;
    }
}
