package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRuntimeStatSnapshot;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** Applies gameplay-relevant derived state from the already-validated local runtime snapshot. */
final class PaperItemRuntimeMaterializer {
    private final ItemCatalog itemCatalog;
    private final PaperManagedItemRuntimeResolver runtimeResolver;

    PaperItemRuntimeMaterializer(MinecraftServerPlugin plugin, ItemCatalog itemCatalog) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.runtimeResolver = new PaperManagedItemRuntimeResolver(
                Objects.requireNonNull(plugin, "plugin"),
                itemCatalog
        );
    }

    /** Must run on the Paper main thread after the runtime-stat cache has accepted an authority-validated snapshot. */
    void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        refreshSection(player, player.getInventory().getStorageContents(), "storage");
        refreshSection(player, player.getInventory().getArmorContents(), "armor");
        refreshSection(player, player.getInventory().getExtraContents(), "extra");
    }

    private void refreshSection(Player player, ItemStack[] contents, String section) {
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) continue;

            ItemRuntimeStatSnapshot snapshot = runtimeResolver.find(
                    player,
                    stack,
                    section + "[" + slot + "]"
            ).orElse(null);
            if (snapshot == null) continue;

            ItemDefinition definition = itemCatalog.require(snapshot.definitionId());
            if (definition.category() != ItemCategory.EQUIPMENT) {
                continue;
            }
            PaperIntrinsicItemAttributes.apply(stack, definition, snapshot);
        }
    }
}
