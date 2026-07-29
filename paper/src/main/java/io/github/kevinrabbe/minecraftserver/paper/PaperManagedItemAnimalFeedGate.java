package io.github.kevinrabbe.minecraftserver.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Animals;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/** Keeps managed value out of vanilla animal-feed/breeding consumption until a feature owns that transition. */
final class PaperManagedItemAnimalFeedGate implements Listener {
    private static final Component FEED_DENIED = Component.text(
            "Managed items cannot be used as vanilla animal feed."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperManagedItemScanner managedItems;

    PaperManagedItemAnimalFeedGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.managedItems = new PaperManagedItemScanner(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnimalInteract(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Animals animal)) {
            return;
        }

        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        if (held.isEmpty() || !animal.isBreedItem(held)) {
            return;
        }

        String source = "animal-feed-" + animal.getType().name().toLowerCase();
        if (!containsManagedOrMalformed(held, source)) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage(FEED_DENIED);
    }

    private boolean containsManagedOrMalformed(ItemStack stack, String source) {
        try {
            return managedItems.containsManaged(stack, source);
        } catch (PaperItemRepresentationException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Blocked malformed managed-item representation at animal-feed boundary " + source,
                    exception
            );
            return true;
        }
    }
}
