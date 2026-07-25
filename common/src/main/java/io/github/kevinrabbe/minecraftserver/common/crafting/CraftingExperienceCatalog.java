package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable lookup ensuring every configured craft XP policy references a real skill. */
public final class CraftingExperienceCatalog {
    private final Map<Key, CraftingExperienceDefinition> definitions;

    public CraftingExperienceCatalog(
            Collection<CraftingExperienceDefinition> definitions,
            SkillProgressionCatalog skillCatalog
    ) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(skillCatalog, "skillCatalog");
        LinkedHashMap<Key, CraftingExperienceDefinition> indexed = new LinkedHashMap<>();
        for (CraftingExperienceDefinition definition : definitions) {
            CraftingExperienceDefinition value = Objects.requireNonNull(
                    definition,
                    "definitions must not contain null"
            );
            skillCatalog.require(value.skillId());
            Key key = new Key(value.recipeId(), value.recipeVersion());
            if (indexed.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate craft XP policy: " + key);
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("crafting experience catalog must not be empty");
        }
        this.definitions = Map.copyOf(indexed);
    }

    public CraftingExperienceDefinition require(String recipeId, int recipeVersion) {
        CraftingExperienceDefinition definition = definitions.get(new Key(recipeId, recipeVersion));
        if (definition == null) {
            throw new CraftingException("No crafting XP policy for recipe version: " + recipeId + "/" + recipeVersion);
        }
        return definition;
    }

    private record Key(String recipeId, int recipeVersion) { }
}
