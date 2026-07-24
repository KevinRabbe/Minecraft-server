package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogException;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import org.bukkit.Material;

/** Paper-specific validation that cannot live in the platform-neutral common module. */
final class PaperItemCatalogValidator {
    private PaperItemCatalogValidator() {
    }

    static void validate(ItemCatalog catalog) {
        for (ItemDefinition definition : catalog.definitions()) {
            Material material = Material.getMaterial(definition.minecraftMaterial());
            if (material == null) {
                throw new ItemCatalogException(
                        "Unknown Paper Material '" + definition.minecraftMaterial()
                                + "' for item " + definition.definitionId()
                );
            }
            if (!material.isItem()) {
                throw new ItemCatalogException(
                        "Paper Material '" + definition.minecraftMaterial()
                                + "' cannot exist as an ItemStack for item " + definition.definitionId()
                );
            }
        }
    }
}
