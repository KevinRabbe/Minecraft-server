package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SalvageCatalogLoaderTest {
    private final ItemCatalog itemCatalog = new ItemCatalog(List.of(
            new ItemDefinition(
                    "equipment.test_sword",
                    "IRON_SWORD",
                    "Test Sword",
                    1,
                    ItemCategory.EQUIPMENT,
                    ItemIdentityKind.INDIVIDUAL
            ),
            new ItemDefinition(
                    "material.test_scrap",
                    "IRON_NUGGET",
                    "Test Scrap",
                    64,
                    ItemCategory.MATERIALS,
                    ItemIdentityKind.COMMODITY
            )
    ));

    @Test
    void loadsStrictValidatedSalvagePolicy() {
        SalvageCatalog catalog = load("""
                {
                  "schema_version": 1,
                  "salvage": [
                    {
                      "item_definition_id": "equipment.test_sword",
                      "coin_return_minor": 125,
                      "commodity_returns": {"material.test_scrap": 2}
                    }
                  ]
                }
                """);

        SalvageDefinition definition = catalog.require("equipment.test_sword");
        assertEquals(125, definition.coinReturnMinor());
        assertEquals(2L, definition.commodityReturns().get("material.test_scrap"));
    }

    @Test
    void rejectsUnknownFieldsAndInvalidAuthorityReferences() {
        assertThrows(SalvageException.class, () -> load("""
                {
                  "schema_version": 1,
                  "salvage": [],
                  "unexpected": true
                }
                """));
        assertThrows(SalvageException.class, () -> load("""
                {
                  "schema_version": 1,
                  "salvage": [
                    {
                      "item_definition_id": "equipment.missing",
                      "coin_return_minor": 0,
                      "commodity_returns": {}
                    }
                  ]
                }
                """));
    }

    private SalvageCatalog load(String json) {
        return new SalvageCatalogLoader().load(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "test",
                itemCatalog
        );
    }
}
