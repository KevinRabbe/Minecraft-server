package io.github.kevinrabbe.minecraftserver.common.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BundledItemCatalogTest {
    @Test
    void bundledCatalogAlwaysPassesCommonValidation() {
        ItemCatalog catalog = new ItemCatalogLoader().loadResource("/content/items.json");
        assertNotNull(catalog);
    }
}
