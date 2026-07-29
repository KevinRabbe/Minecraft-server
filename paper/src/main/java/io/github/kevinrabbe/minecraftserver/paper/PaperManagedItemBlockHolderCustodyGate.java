package io.github.kevinrabbe.minecraftserver.paper;

import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/** Keeps network-managed ItemStacks out of direct-interaction block holders with no durable network custody. */
final class PaperManagedItemBlockHolderCustodyGate implements Listener {
    private static final Component HOLDER_DENIED = Component.text(
            "Managed items cannot be stored in vanilla lecterns or flower pots."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperItemIdentityCodec identityCodec;

    PaperManagedItemBlockHolderCustodyGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identityCodec = new PaperItemIdentityCodec(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInsertLecternBook(PlayerInsertLecternBookEvent event) {
        if (!isManagedOrMalformed(event.getBook(), "lectern-insert")) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(HOLDER_DENIED);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTakeLecternBook(PlayerTakeLecternBookEvent event) {
        if (!isManagedOrMalformed(event.getBook(), "lectern-take")) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(HOLDER_DENIED);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlowerPotManipulate(PlayerFlowerPotManipulateEvent event) {
        if (!isManagedOrMalformed(event.getItem(), "flower-pot-" + (event.isPlacing() ? "place" : "take"))) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(HOLDER_DENIED);
    }

    private boolean isManagedOrMalformed(ItemStack stack, String source) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        try {
            return identityCodec.readClaim(stack, source).isPresent();
        } catch (PaperItemRepresentationException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Blocked malformed managed-item identity at block-holder custody boundary " + source,
                    exception
            );
            return true;
        }
    }
}
