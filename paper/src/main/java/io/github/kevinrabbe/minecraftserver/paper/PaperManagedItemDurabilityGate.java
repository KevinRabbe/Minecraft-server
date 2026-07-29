package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Keeps vanilla durability outside managed-item state until durability has an explicit persistent authority contract.
 *
 * <p>Individual item delivery currently reconstructs representations from stable authority fields rather than carrying
 * arbitrary mutable ItemStack state between custody locations. Allowing vanilla durability to change in player inventory
 * would therefore make AH/trade/storage round-trips an implicit repair path. Damage and Mending both remain closed until
 * durability is deliberately persisted or deliberately excluded from the product.</p>
 */
final class PaperManagedItemDurabilityGate implements Listener {
    private final MinecraftServerPlugin plugin;
    private final PaperManagedItemScanner managedItems;

    PaperManagedItemDurabilityGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.managedItems = new PaperManagedItemScanner(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (isManagedOrMalformed(event.getItem(), "durability-damage")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMend(PlayerItemMendEvent event) {
        if (isManagedOrMalformed(event.getItem(), "durability-mend")) {
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
                    "Blocked malformed managed-item representation at durability boundary " + source,
                    exception
            );
            return true;
        }
    }
}
