package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.item.ItemRollProfile;

import java.util.Objects;

/** One immutable recipe version plus the intrinsic roll profile applied to individualized output. */
public record CraftRecipeVersion(
        int version,
        CraftRecipeDefinition recipe,
        ItemRollProfile outputRollProfile
) {
    public CraftRecipeVersion {
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
        recipe = Objects.requireNonNull(recipe, "recipe");
        outputRollProfile = Objects.requireNonNull(outputRollProfile, "outputRollProfile");
    }
}
