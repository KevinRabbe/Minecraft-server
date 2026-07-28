package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalogLoader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingItemRollOwnershipTest {
    private final ItemCatalog items = new ItemCatalogLoader().loadResource("/content/items.json");
    private final SkillProgressionCatalog skills = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");
    private final CraftingContentCatalogLoader loader = new CraftingContentCatalogLoader();

    @Test
    void bundledRecipeUsesTheOutputItemDefinitionRollProfile() {
        CraftingContentCatalog content = loader.loadResource("/content/crafting.json", items, skills);
        CraftRecipeVersion recipe = content.recipes().require("starter.sword", 1);

        assertEquals(
                items.require("equipment.starter_sword").rollProfile(),
                recipe.outputRollProfile()
        );
    }

    @Test
    void recipeCannotRedefineIntrinsicRollSemanticsForTheSameItem() {
        CraftingException exception = assertThrows(CraftingException.class, () -> loader.load(
                stream("""
                        {
                          "schema_version": 1,
                          "recipes": [
                            {
                              "recipe_id": "starter.sword",
                              "version": 1,
                              "ingredients": [
                                {"definition_id": "material.raw_iron", "quantity": 2},
                                {"definition_id": "material.oak_log", "quantity": 1}
                              ],
                              "output_definition_id": "equipment.starter_sword",
                              "output_quantity": 1,
                              "required_skill_id": null,
                              "required_skill_level": 0,
                              "roll_properties": {
                                "damage": {
                                  "minimum_basis_points": 10000,
                                  "maximum_basis_points": 13000
                                }
                              },
                              "experience_skill_id": "crafting",
                              "requested_experience": 25
                            }
                          ]
                        }
                        """),
                "roll-ownership-mismatch",
                items,
                skills
        ));

        assertTrue(exception.getMessage().contains(
                "recipe roll profile must exactly match output item definition equipment.starter_sword"
        ));
    }

    @Test
    void recipeCannotSilentlyOmitAProfileOwnedByItsOutputItem() {
        CraftingException exception = assertThrows(CraftingException.class, () -> loader.load(
                stream("""
                        {
                          "schema_version": 1,
                          "recipes": [
                            {
                              "recipe_id": "starter.sword",
                              "version": 1,
                              "ingredients": [
                                {"definition_id": "material.raw_iron", "quantity": 2},
                                {"definition_id": "material.oak_log", "quantity": 1}
                              ],
                              "output_definition_id": "equipment.starter_sword",
                              "output_quantity": 1,
                              "required_skill_id": null,
                              "required_skill_level": 0,
                              "roll_properties": {},
                              "experience_skill_id": "crafting",
                              "requested_experience": 25
                            }
                          ]
                        }
                        """),
                "roll-ownership-omission",
                items,
                skills
        ));

        assertTrue(exception.getMessage().contains(
                "recipe roll profile must exactly match output item definition equipment.starter_sword"
        ));
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
