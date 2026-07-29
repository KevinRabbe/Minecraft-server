package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Keeps vanilla item-use transformations outside managed value until an explicit feature adapter owns them.
 *
 * <p>Only right-click use of non-block managed items is denied here. Interacting with the clicked block remains allowed,
 * and direct placement semantics for a top-level managed block remain a separate product decision. Consumption is also
 * cancelled as a backstop so food/potion-style managed value cannot disappear through a vanilla path.</p>
 */
final class PaperManagedItemNativeUseGate implements Listener {
    private final MinecraftServerPlugin plugin;
    private final PaperManagedItemScanner managedItems;

    PaperManagedItemNativeUseGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.managedItems = new PaperManagedItemScanner(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if ((action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
                || !event.hasItem()
                || event.isBlockInHand()) {
            return;
        }

        ItemStack item = event.getItem();
        if (isManagedOrMalformed(item, "native-use-interact")) {
            // Do not cancel the whole interaction: a chest/door/etc. may still be used while holding the managed item.
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isManagedOrMalformed(event.getItem(), "native-use-consume")) {
            event.setCancelled(true);
        }
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
                    "Blocked malformed managed-item representation at native-use boundary " + source,
                    exception
            );
            return true;
        }
    }
}
