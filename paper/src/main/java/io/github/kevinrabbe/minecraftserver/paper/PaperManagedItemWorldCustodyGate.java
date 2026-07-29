package io.github.kevinrabbe.minecraftserver.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Keeps network-managed item representations inside persistent custody instead of disposable world Item entities.
 *
 * <p>This is not soulbinding: Auction House, secure trade, clan storage and other explicit custody transitions remain
 * unchanged. A future feature that intentionally places managed value into the world must add a durable custody model
 * for that exact feature rather than relying on vanilla Item-entity lifetime.</p>
 */
final class PaperManagedItemWorldCustodyGate implements Listener {
    private static final Component DROP_DENIED = Component.text(
            "Managed items must be transferred through an authoritative trade or storage path."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperManagedItemScanner managedItems;

    PaperManagedItemWorldCustodyGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.managedItems = new PaperManagedItemScanner(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (!isManagedOrMalformed(event.getItemDrop().getItemStack(), "player-drop")) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(DROP_DENIED);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Iterator<ItemStack> drops = event.getDrops().iterator();
        int index = 0;
        while (drops.hasNext()) {
            ItemStack stack = drops.next();
            if (isManagedOrMalformed(stack, "player-death-drop[" + index + "]")) {
                drops.remove();
                if (!event.getItemsToKeep().contains(stack)) {
                    event.getItemsToKeep().add(stack);
                }
            }
            index++;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (isManagedOrMalformed(event.getItem().getItemStack(), "world-entity-pickup")) {
            event.setCancelled(true);
            if (event.getEntity() instanceof Player player) {
                player.sendMessage(Component.text(
                        "That managed world item is outside valid persistent custody and cannot be picked up."
                ));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (isManagedOrMalformed(event.getItem().getItemStack(), "world-inventory-pickup")) {
            event.setCancelled(true);
        }
    }

    private boolean isManagedOrMalformed(ItemStack stack, String source) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        try {
            return managedItems.containsManaged(stack, source);
        } catch (PaperItemRepresentationException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Blocked malformed managed-item identity at world-custody boundary " + source,
                    exception
            );
            return true;
        }
    }
}
