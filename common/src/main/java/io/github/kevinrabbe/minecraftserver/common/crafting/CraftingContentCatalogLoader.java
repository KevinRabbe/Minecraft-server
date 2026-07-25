package io.github.kevinrabbe.minecraftserver.common.crafting;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRollProfile;
import io.github.kevinrabbe.minecraftserver.common.item.RollRange;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict JSON loader for immutable crafting recipes plus their exactly-once XP policies. */
public final class CraftingContentCatalogLoader {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public CraftingContentCatalog load(
            Path path,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        Objects.requireNonNull(path, "path");
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.toString(), itemCatalog, skillCatalog);
        } catch (IOException exception) {
            throw new CraftingException("Could not read crafting content: " + path, exception);
        }
    }

    public CraftingContentCatalog loadResource(
            String resourcePath,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        InputStream input = CraftingContentCatalogLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new CraftingException("Crafting content resource does not exist: " + resourcePath);
        }
        try (input) {
            return load(input, resourcePath, itemCatalog, skillCatalog);
        } catch (IOException exception) {
            throw new CraftingException("Could not close crafting content resource: " + resourcePath, exception);
        }
    }

    CraftingContentCatalog load(
            InputStream input,
            String sourceDescription,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        Objects.requireNonNull(skillCatalog, "skillCatalog");
        String source = sourceDescription == null || sourceDescription.isBlank()
                ? "<stream>"
                : sourceDescription.trim();

        final RawCatalog raw;
        try {
            raw = objectMapper.readValue(input, RawCatalog.class);
        } catch (IOException exception) {
            throw new CraftingException("Invalid crafting content JSON in " + source, exception);
        }
        if (raw == null || raw.recipes() == null) {
            throw new CraftingException("Crafting content must contain a recipes array: " + source);
        }
        if (raw.schemaVersion() != SCHEMA_VERSION) {
            throw new CraftingException(
                    "Unsupported crafting schema_version " + raw.schemaVersion()
                            + " in " + source + "; expected " + SCHEMA_VERSION
            );
        }

        ArrayList<CraftRecipeVersion> recipes = new ArrayList<>(raw.recipes().size());
        ArrayList<CraftingExperienceDefinition> experience = new ArrayList<>(raw.recipes().size());
        for (int index = 0; index < raw.recipes().size(); index++) {
            RawRecipe value = raw.recipes().get(index);
            if (value == null) {
                throw new CraftingException("recipes[" + index + "] must not be null in " + source);
            }
            try {
                ArrayList<RecipeIngredient> ingredients = new ArrayList<>();
                if (value.ingredients() == null) {
                    throw new IllegalArgumentException("ingredients must not be null");
                }
                for (RawIngredient ingredient : value.ingredients()) {
                    if (ingredient == null) {
                        throw new IllegalArgumentException("ingredients must not contain null");
                    }
                    ingredients.add(new RecipeIngredient(ingredient.definitionId(), ingredient.quantity()));
                }

                SkillId requiredSkill = value.requiredSkillId() == null || value.requiredSkillId().isBlank()
                        ? null
                        : new SkillId(value.requiredSkillId());
                CraftRecipeDefinition recipe = new CraftRecipeDefinition(
                        value.recipeId(),
                        ingredients,
                        value.outputDefinitionId(),
                        value.outputQuantity(),
                        requiredSkill,
                        value.requiredSkillLevel()
                );

                LinkedHashMap<String, RollRange> rolls = new LinkedHashMap<>();
                Map<String, RawRollRange> rawRolls = value.rollProperties() == null
                        ? Map.of()
                        : value.rollProperties();
                rawRolls.forEach((propertyId, range) -> {
                    if (range == null) {
                        throw new IllegalArgumentException("roll property must not be null: " + propertyId);
                    }
                    rolls.put(propertyId, new RollRange(range.minimumBasisPoints(), range.maximumBasisPoints()));
                });

                recipes.add(new CraftRecipeVersion(
                        value.version(),
                        recipe,
                        new ItemRollProfile(rolls)
                ));
                experience.add(new CraftingExperienceDefinition(
                        value.recipeId(),
                        value.version(),
                        new SkillId(value.experienceSkillId()),
                        value.requestedExperience()
                ));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new CraftingException(
                        "Invalid crafting recipe at recipes[" + index + "] in " + source + ": "
                                + exception.getMessage(),
                        exception
                );
            }
        }

        CraftRecipeCatalog recipeCatalog = new CraftRecipeCatalog(recipes, itemCatalog);
        CraftingExperienceCatalog experienceCatalog = new CraftingExperienceCatalog(experience, skillCatalog);
        for (CraftRecipeVersion recipe : recipes) {
            experienceCatalog.require(recipe.recipe().recipeId(), recipe.version());
        }
        return new CraftingContentCatalog(recipeCatalog, experienceCatalog);
    }

    private record RawCatalog(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("recipes") List<RawRecipe> recipes
    ) { }

    private record RawRecipe(
            @JsonProperty("recipe_id") String recipeId,
            @JsonProperty("version") int version,
            @JsonProperty("ingredients") List<RawIngredient> ingredients,
            @JsonProperty("output_definition_id") String outputDefinitionId,
            @JsonProperty("output_quantity") int outputQuantity,
            @JsonProperty("required_skill_id") String requiredSkillId,
            @JsonProperty("required_skill_level") int requiredSkillLevel,
            @JsonProperty("roll_properties") Map<String, RawRollRange> rollProperties,
            @JsonProperty("experience_skill_id") String experienceSkillId,
            @JsonProperty("requested_experience") long requestedExperience
    ) { }

    private record RawIngredient(
            @JsonProperty("definition_id") String definitionId,
            @JsonProperty("quantity") long quantity
    ) { }

    private record RawRollRange(
            @JsonProperty("minimum_basis_points") int minimumBasisPoints,
            @JsonProperty("maximum_basis_points") int maximumBasisPoints
    ) { }
}
