package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemCatalogLoaderTest {
    private final ItemCatalogLoader loader = new ItemCatalogLoader();

    @Test
    void loadsValidCommodityAndIndividualDefinitions() {
        ItemCatalog catalog = load("""
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "definition_id": "material.iron_ore",
                      "minecraft_material": "RAW_IRON",
                      "display_name": "Iron Ore",
                      "max_stack_size": 64,
                      "category": "materials",
                      "identity_kind": "commodity"
                    },
                    {
                      "definition_id": "equipment.steel_pickaxe",
                      "minecraft_material": "IRON_PICKAXE",
                      "display_name": "Steel Pickaxe",
                      "max_stack_size": 1,
                      "category": "equipment",
                      "identity_kind": "individual"
                    }
                  ]
                }
                """);

        assertEquals(2, catalog.size());
        ItemDefinition commodity = catalog.require("material.iron_ore");
        assertEquals(ItemIdentityKind.COMMODITY, commodity.identityKind());
        assertTrue(commodity.stackable());
        assertTrue(commodity.useRequirements().unrestricted());

        ItemDefinition individual = catalog.require("equipment.steel_pickaxe");
        assertEquals(ItemIdentityKind.INDIVIDUAL, individual.identityKind());
        assertEquals(1, individual.maxStackSize());
        assertTrue(individual.useRequirements().unrestricted());
    }

    @Test
    void loadsSkillUseRequirementsWithoutChangingOwnershipModel() {
        ItemCatalog catalog = load("""
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "definition_id": "equipment.special_sword",
                      "minecraft_material": "IRON_SWORD",
                      "display_name": "Special Sword",
                      "max_stack_size": 1,
                      "category": "equipment",
                      "identity_kind": "individual",
                      "use_skill_requirements": {
                        "combat": 10,
                        "bounty.spider": 5
                      }
                    }
                  ]
                }
                """);

        ItemDefinition definition = catalog.require("equipment.special_sword");
        assertEquals(ItemIdentityKind.INDIVIDUAL, definition.identityKind());
        assertEquals(
                List.of(
                        new ItemSkillRequirement(new SkillId("bounty.spider"), 5),
                        new ItemSkillRequirement(new SkillId("combat"), 10)
                ),
                definition.useRequirements().skillRequirements()
        );
    }

    @Test
    void rejectsInvalidUseSkillRequirementLevel() {
        ItemCatalogException exception = assertThrows(ItemCatalogException.class, () -> load("""
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "definition_id": "equipment.bad_requirement",
                      "minecraft_material": "IRON_SWORD",
                      "display_name": "Bad Requirement",
                      "max_stack_size": 1,
                      "category": "equipment",
                      "identity_kind": "individual",
                      "use_skill_requirements": {
                        "combat": 101
                      }
                    }
                  ]
                }
                """));

        assertTrue(exception.getMessage().contains("minimumLevel must be between 1 and 100"));
    }

    @Test
    void rejectsDuplicateDefinitionIds() {
        ItemCatalogException exception = assertThrows(ItemCatalogException.class, () -> load("""
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "definition_id": "material.coal",
                      "minecraft_material": "COAL",
                      "display_name": "Coal",
                      "max_stack_size": 64,
                      "category": "materials",
                      "identity_kind": "commodity"
                    },
                    {
                      "definition_id": "material.coal",
                      "minecraft_material": "CHARCOAL",
                      "display_name": "Other Coal",
                      "max_stack_size": 64,
                      "category": "materials",
                      "identity_kind": "commodity"
                    }
                  ]
                }
                """));

        assertTrue(exception.getMessage().contains("Duplicate item definition_id"));
    }

    @Test
    void rejectsUnknownJsonFields() {
        assertThrows(ItemCatalogException.class, () -> load("""
                {
                  "schema_version": 1,
                  "items": [],
                  "magic_market_type": "bazaar"
                }
                """));
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        ItemCatalogException exception = assertThrows(ItemCatalogException.class, () -> load("""
                {
                  "schema_version": 2,
                  "items": []
                }
                """));

        assertTrue(exception.getMessage().contains("Unsupported item catalog schema_version"));
    }

    @Test
    void rejectsNonStackableCommodity() {
        ItemCatalogException exception = assertThrows(ItemCatalogException.class, () -> load(singleItem(
                "material.bad_commodity",
                "STONE",
                1,
                "materials",
                "commodity"
        )));

        assertTrue(exception.getMessage().contains("COMMODITY definitions must be stackable"));
    }

    @Test
    void rejectsStackableIndividualItem() {
        ItemCatalogException exception = assertThrows(ItemCatalogException.class, () -> load(singleItem(
                "equipment.bad_unique",
                "IRON_SWORD",
                64,
                "equipment",
                "individual"
        )));

        assertTrue(exception.getMessage().contains("INDIVIDUAL definitions must have maxStackSize=1"));
    }

    @Test
    void rejectsIntrinsicRollProfileOutsideEquipmentCategory() {
        ItemCatalogException exception = assertThrows(ItemCatalogException.class, () -> load("""
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "definition_id": "map.bad_rolled_map",
                      "minecraft_material": "MAP",
                      "display_name": "Bad Rolled Map",
                      "max_stack_size": 1,
                      "category": "progression",
                      "identity_kind": "individual",
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

        assertTrue(exception.getMessage().contains("only valid for EQUIPMENT"));
    }

    @Test
    void rejectsInvalidStableIdAndImpossibleStackSize() {
        assertThrows(ItemCatalogException.class, () -> load(singleItem(
                "Bad ID",
                "STONE",
                64,
                "materials",
                "commodity"
        )));
        assertThrows(ItemCatalogException.class, () -> load(singleItem(
                "material.too_large",
                "STONE",
                100,
                "materials",
                "commodity"
        )));
    }

    @Test
    void unknownDefinitionFailsClosed() {
        ItemCatalog catalog = load("""
                {
                  "schema_version": 1,
                  "items": []
                }
                """);

        assertThrows(ItemCatalogException.class, () -> catalog.require("material.missing"));
    }

    private ItemCatalog load(String json) {
        return loader.load(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "test-catalog"
        );
    }

    private static String singleItem(
            String definitionId,
            String minecraftMaterial,
            int maxStackSize,
            String category,
            String identityKind
    ) {
        return """
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "definition_id": "%s",
                      "minecraft_material": "%s",
                      "display_name": "Test Item",
                      "max_stack_size": %d,
                      "category": "%s",
                      "identity_kind": "%s"
                    }
                  ]
                }
                """.formatted(definitionId, minecraftMaterial, maxStackSize, category, identityKind);
    }
}
