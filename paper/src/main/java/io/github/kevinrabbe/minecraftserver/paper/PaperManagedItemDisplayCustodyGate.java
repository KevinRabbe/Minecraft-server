package io.github.kevinrabbe.minecraftserver.paper;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Keeps network-managed ItemStacks out of display/entity equipment holders that have no durable network custody model.
 *
 * <p>This is a custody fence, not a cosmetic restriction. Ordinary untracked items may still use item frames and armor
 * stands normally. Future managed display/equipment features must add explicit persistent custody before reopening these
 * transitions.</p>
 */
final class PaperManagedItemDisplayCustodyGate implements Listener {
    private static final Component DISPLAY_DENIED = Component.text(
            "Managed items cannot be stored in item frames or armor stands."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperItemIdentityCodec identityCodec;

    PaperManagedItemDisplayCustodyGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identityCodec = new PaperItemIdentityCodec(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemFrameChange(PlayerItemFrameChangeEvent event) {
        if (!isManagedOrMalformed(event.getItemStack(), "item-frame-" + event.getAction().name().toLowerCase())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(DISPLAY_DENIED);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (!isManagedOrMalformed(event.getPlayerItem(), "armor-stand-player-item")
                && !isManagedOrMalformed(event.getArmorStandItem(), "armor-stand-held-item")) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(DISPLAY_DENIED);
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
                    "Blocked malformed managed-item identity at display-custody boundary " + source,
                    exception
            );
            return true;
        }
    }
}
