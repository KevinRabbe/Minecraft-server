package io.github.kevinrabbe.minecraftserver.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/** Prevents Creative inventory packets from becoming an unaudited mint/destroy/custody path for managed value. */
final class PaperManagedItemCreativeCustodyGate implements Listener {
    private static final Component CREATIVE_DENIED = Component.text(
            "Managed items cannot be manipulated while using Creative inventory mode."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperManagedItemScanner managedItems;

    PaperManagedItemCreativeCustodyGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.managedItems = new PaperManagedItemScanner(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventoryMutation(InventoryCreativeEvent event) {
        if (!isManagedOrMalformed(event.getCursor(), "creative-cursor")
                && !isManagedOrMalformed(event.getCurrentItem(), "creative-current")) {
            return;
        }
        event.setCancelled(true);
        event.getWhoClicked().sendMessage(CREATIVE_DENIED);
    }

    private boolean isManagedOrMalformed(ItemStack stack, String source) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return managedItems.containsManaged(stack, source);
        } catch (PaperItemRepresentationException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Blocked malformed managed-item representation at Creative inventory boundary " + source,
                    exception
            );
            return true;
        }
    }
}
