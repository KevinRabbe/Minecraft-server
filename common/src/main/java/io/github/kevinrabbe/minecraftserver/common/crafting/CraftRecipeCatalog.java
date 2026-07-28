package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable validated lookup of recipe versions for one content release. */
public final class CraftRecipeCatalog {
    private final Map<Key, CraftRecipeVersion> recipes;

    public CraftRecipeCatalog(Collection<CraftRecipeVersion> versions, ItemCatalog itemCatalog) {
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        LinkedHashMap<Key, CraftRecipeVersion> indexed = new LinkedHashMap<>();
        for (CraftRecipeVersion version : versions) {
            CraftRecipeVersion nonNull = Objects.requireNonNull(version, "versions must not contain null");
            validateRecipe(nonNull, itemCatalog);
            Key key = new Key(nonNull.recipe().recipeId(), nonNull.version());
            if (indexed.putIfAbsent(key, nonNull) != null) {
                throw new IllegalArgumentException("duplicate craft recipe version: " + key);
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("craft recipe catalog must not be empty");
        }
        recipes = Map.copyOf(indexed);
    }

    public CraftRecipeVersion require(String recipeId, int version) {
        CraftRecipeVersion recipe = recipes.get(new Key(recipeId, version));
        if (recipe == null) {
            throw new CraftingException("Unknown craft recipe version: " + recipeId + "/" + version);
        }
        return recipe;
    }

    public List<CraftRecipeVersion> all() {
        return recipes.values().stream()
                .sorted((left, right) -> {
                    int byId = left.recipe().recipeId().compareTo(right.recipe().recipeId());
                    return byId != 0 ? byId : Integer.compare(left.version(), right.version());
                })
                .toList();
    }

    private static void validateRecipe(CraftRecipeVersion version, ItemCatalog itemCatalog) {
        CraftRecipeDefinition recipe = version.recipe();
        for (RecipeIngredient ingredient : recipe.ingredients()) {
            ItemDefinition definition = itemCatalog.require(ingredient.definitionId());
            if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                throw new IllegalArgumentException(
                        "craft ingredients must be COMMODITY definitions: " + definition.definitionId()
                );
            }
        }
        ItemDefinition output = itemCatalog.require(recipe.outputDefinitionId());
        if (output.identityKind() == ItemIdentityKind.INDIVIDUAL && recipe.outputQuantity() != 1) {
            throw new IllegalArgumentException("INDIVIDUAL craft outputQuantity must be exactly 1");
        }
        if (!version.outputRollProfile().equals(output.rollProfile())) {
            throw new IllegalArgumentException(
                    "craft recipe roll profile must exactly match output item definition " + output.definitionId()
            );
        }
        if (output.identityKind() == ItemIdentityKind.COMMODITY && version.outputRollProfile().rolled()) {
            throw new IllegalArgumentException("COMMODITY craft outputs cannot have intrinsic roll profiles");
        }
    }

    private record Key(String recipeId, int version) {
        private Key {
            if (recipeId == null || recipeId.isBlank() || version < 0) {
                throw new IllegalArgumentException("invalid recipe key");
            }
        }
    }
}
