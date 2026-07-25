package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalogLoader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingContentCatalogLoaderTest {
    @Test
    void bundledStarterSwordRecipeBindsRollAndCraftingXpPolicy() {
        ItemCatalog items = new ItemCatalogLoader().loadResource("/content/items.json");
        SkillProgressionCatalog skills = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");

        CraftingContentCatalog content = new CraftingContentCatalogLoader().loadResource(
                "/content/crafting.json",
                items,
                skills
        );
        CraftRecipeVersion recipe = content.recipes().require("starter.sword", 1);
        CraftingExperienceDefinition experience = content.experience().require("starter.sword", 1);

        assertEquals(2, recipe.recipe().ingredients().size());
        assertEquals("equipment.starter_sword", recipe.recipe().outputDefinitionId());
        assertEquals(1, recipe.recipe().outputQuantity());
        assertTrue(recipe.outputRollProfile().rolled());
        assertEquals(10_000, recipe.outputRollProfile().properties().get("damage").minimumBasisPoints());
        assertEquals(12_000, recipe.outputRollProfile().properties().get("damage").maximumBasisPoints());
        assertEquals(new SkillId("crafting"), experience.skillId());
        assertEquals(25L, experience.requestedExperience());
    }

    @Test
    void unknownFieldsAndMissingXpPolicyFailClosed() {
        ItemCatalog items = new ItemCatalogLoader().loadResource("/content/items.json");
        SkillProgressionCatalog skills = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");
        CraftingContentCatalogLoader loader = new CraftingContentCatalogLoader();

        String unknownField = """
                {
                  "schema_version": 1,
                  "recipes": [
                    {
                      "recipe_id": "starter.sword",
                      "version": 1,
                      "ingredients": [
                        {"definition_id": "material.raw_iron", "quantity": 2}
                      ],
                      "output_definition_id": "equipment.starter_sword",
                      "output_quantity": 1,
                      "required_skill_id": null,
                      "required_skill_level": 0,
                      "roll_properties": {},
                      "experience_skill_id": "crafting",
                      "requested_experience": 25,
                      "unexpected": true
                    }
                  ]
                }
                """;
        assertThrows(
                CraftingException.class,
                () -> loader.load(stream(unknownField), "unknown-field-test", items, skills)
        );

        String missingXp = """
                {
                  "schema_version": 1,
                  "recipes": [
                    {
                      "recipe_id": "starter.sword",
                      "version": 1,
                      "ingredients": [
                        {"definition_id": "material.raw_iron", "quantity": 2}
                      ],
                      "output_definition_id": "equipment.starter_sword",
                      "output_quantity": 1,
                      "required_skill_id": null,
                      "required_skill_level": 0,
                      "roll_properties": {},
                      "experience_skill_id": null,
                      "requested_experience": 0
                    }
                  ]
                }
                """;
        assertThrows(
                CraftingException.class,
                () -> loader.load(stream(missingXp), "missing-xp-test", items, skills)
        );
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
