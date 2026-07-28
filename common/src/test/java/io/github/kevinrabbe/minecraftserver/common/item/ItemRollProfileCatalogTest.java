package io.github.kevinrabbe.minecraftserver.common.item;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemRollProfileCatalogTest {
    private final ItemCatalogLoader loader = new ItemCatalogLoader();

    @Test
    void loadsIntrinsicRollProfileForIndividualDefinition() {
        ItemCatalog catalog = load("""
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "definition_id": "equipment.test_blade",
                      "minecraft_material": "IRON_SWORD",
                      "display_name": "Test Blade",
                      "max_stack_size": 1,
                      "category": "equipment",
                      "identity_kind": "individual",
                      "roll_properties": {
                        "damage": {
                          "minimum_basis_points": 9500,
                          "maximum_basis_points": 11500
                        }
                      }
                    }
                  ]
                }
                """);

        assertEquals(
                new ItemRollProfile(Map.of("damage", new RollRange(9_500, 11_500))),
                catalog.require("equipment.test_blade").rollProfile()
        );
    }

    @Test
    void omittedRollProfileRemainsExplicitlyNone() {
        ItemCatalog catalog = load("""
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "definition_id": "equipment.test_plain",
                      "minecraft_material": "IRON_SWORD",
                      "display_name": "Plain Blade",
                      "max_stack_size": 1,
                      "category": "equipment",
                      "identity_kind": "individual"
                    }
                  ]
                }
                """);

        assertEquals(ItemRollProfile.NONE, catalog.require("equipment.test_plain").rollProfile());
    }

    @Test
    void commodityDefinitionsCannotAcquireIndividualRollState() {
        ItemCatalogException exception = assertThrows(ItemCatalogException.class, () -> load("""
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "definition_id": "material.bad_roll",
                      "minecraft_material": "IRON_INGOT",
                      "display_name": "Bad Roll",
                      "max_stack_size": 64,
                      "category": "materials",
                      "identity_kind": "commodity",
                      "roll_properties": {
                        "damage": {
                          "minimum_basis_points": 10000,
                          "maximum_basis_points": 12000
                        }
                      }
                    }
                  ]
                }
                """));

        assertTrue(exception.getMessage().contains("COMMODITY definitions cannot have intrinsic roll profiles"));
    }

    private ItemCatalog load(String json) {
        return loader.load(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "roll-profile-test"
        );
    }
}
