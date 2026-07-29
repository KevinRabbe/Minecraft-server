package io.github.kevinrabbe.minecraftserver.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/** Prevents direct player composting from consuming managed value outside an authoritative settlement path. */
final class PaperManagedItemComposterGate implements Listener {
    private static final Component COMPOST_DENIED = Component.text(
            "Managed items cannot be composted through vanilla block interaction."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperManagedItemScanner managedItems;

    PaperManagedItemComposterGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.managedItems = new PaperManagedItemScanner(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onComposterInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !event.hasItem()) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.COMPOSTER) {
            return;
        }

        // A full composter's interaction extracts bone meal and does not consume the held item.
        if (clicked.getBlockData() instanceof Levelled levelled
                && levelled.getLevel() >= levelled.getMaximumLevel()) {
            return;
        }

        ItemStack item = event.getItem();
        if (!isManagedOrMalformed(item, "composter-interact")) {
            return;
        }

        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.getPlayer().sendMessage(COMPOST_DENIED);
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
                    "Blocked malformed managed-item representation at composter boundary " + source,
                    exception
            );
            return true;
        }
    }
}
