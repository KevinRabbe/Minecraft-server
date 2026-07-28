package io.github.kevinrabbe.minecraftserver.common.crafting;

import java.util.Objects;

/** One validated immutable crafting-content release. */
public record CraftingContentCatalog(
        CraftRecipeCatalog recipes,
        CraftingExperienceCatalog experience
) {
    public CraftingContentCatalog {
        recipes = Objects.requireNonNull(recipes, "recipes");
        experience = Objects.requireNonNull(experience, "experience");
    }
}
