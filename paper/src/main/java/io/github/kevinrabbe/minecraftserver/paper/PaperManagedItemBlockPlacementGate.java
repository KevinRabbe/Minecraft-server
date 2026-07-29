package io.github.kevinrabbe.minecraftserver.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/** Prevents managed value from entering world-block custody before placement has an authoritative settlement adapter. */
final class PaperManagedItemBlockPlacementGate implements Listener {
    private static final Component PLACEMENT_DENIED = Component.text(
            "Managed items cannot be placed as world blocks yet."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperManagedItemScanner managedItems;

    PaperManagedItemBlockPlacementGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.managedItems = new PaperManagedItemScanner(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!containsManagedOrMalformed(event.getItemInHand(), "block-place")) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(PLACEMENT_DENIED);
    }

    private boolean containsManagedOrMalformed(ItemStack stack, String source) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return managedItems.containsManaged(stack, source);
        } catch (PaperItemRepresentationException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Blocked malformed managed-item representation at block-placement custody boundary " + source,
                    exception
            );
            return true;
        }
    }
}
