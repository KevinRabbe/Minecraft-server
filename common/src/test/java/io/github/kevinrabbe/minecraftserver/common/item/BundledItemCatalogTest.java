package io.github.kevinrabbe.minecraftserver.common.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BundledItemCatalogTest {
    @Test
    void bundledCatalogAlwaysPassesCommonValidation() {
        ItemCatalog catalog = new ItemCatalogLoader().loadResource("/content/items.json");
        assertNotNull(catalog);
    }

    @Test
    void starterSwordRemainsCompatibleWithFrozenLegacyClanWarV1() {
        ItemCatalog catalog = new ItemCatalogLoader().loadResource("/content/items.json");
        ItemDefinition starterSword = catalog.require("equipment.starter_sword");

        assertEquals("IRON_SWORD", starterSword.minecraftMaterial());
        assertEquals(1, starterSword.maxStackSize());
        assertEquals(ItemCategory.EQUIPMENT, starterSword.category());
        assertEquals(ItemIdentityKind.INDIVIDUAL, starterSword.identityKind());
    }
}
