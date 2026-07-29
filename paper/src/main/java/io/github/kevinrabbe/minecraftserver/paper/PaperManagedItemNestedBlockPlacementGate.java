package io.github.kevinrabbe.minecraftserver.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Prevents an otherwise ordinary placeable ItemStack from moving nested managed value into world block-entity custody.
 *
 * <p>This gate intentionally ignores a managed identity on the outer placed stack itself. Whether a directly managed
 * block/material may be placed is a separate gameplay/content decision. Only managed value nested below that outer
 * stack is fenced here.</p>
 */
final class PaperManagedItemNestedBlockPlacementGate implements Listener {
    private static final Component PLACEMENT_DENIED = Component.text(
            "Containers holding managed items cannot be placed in the world."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperManagedItemScanner managedItems;

    PaperManagedItemNestedBlockPlacementGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.managedItems = new PaperManagedItemScanner(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!containsNestedManagedOrMalformed(event.getItemInHand(), "block-place")) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(PLACEMENT_DENIED);
    }

    private boolean containsNestedManagedOrMalformed(ItemStack stack, String source) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return managedItems.containsNestedManaged(stack, source);
        } catch (PaperItemRepresentationException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Blocked malformed nested managed-item representation at block-placement custody boundary " + source,
                    exception
            );
            return true;
        }
    }
}
