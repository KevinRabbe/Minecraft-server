package io.github.kevinrabbe.minecraftserver.common.crafting;

import java.util.regex.Pattern;

/** One fungible item-definition requirement in a recipe. */
public record RecipeIngredient(String definitionId, long quantity) {
    private static final Pattern DEFINITION_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public RecipeIngredient {
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (!DEFINITION_ID.matcher(definitionId).matches()) {
            throw new IllegalArgumentException("definitionId has invalid format: " + definitionId);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
    }
}
