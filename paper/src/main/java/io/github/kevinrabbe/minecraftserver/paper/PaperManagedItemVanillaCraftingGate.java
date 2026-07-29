package io.github.kevinrabbe.minecraftserver.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Prevents network-managed ItemStacks from entering or being minted by the vanilla crafting-table recipe path.
 *
 * <p>Managed recipe settlement belongs to the fenced persistent crafting authority. This gate does not decide whether
 * ordinary untracked vanilla recipes are enabled; it only prevents a managed representation from crossing a recipe
 * boundary that does not own network value semantics.</p>
 */
final class PaperManagedItemVanillaCraftingGate implements Listener {
    private static final Component MANAGED_RECIPE_MESSAGE = Component.text(
            "That managed item must be crafted through the server's authoritative recipe system."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperItemIdentityCodec identityCodec;

    PaperManagedItemVanillaCraftingGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identityCodec = new PaperItemIdentityCodec(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        CraftingInventory inventory = event.getInventory();
        if (containsManagedOrMalformed(inventory.getMatrix(), "vanilla-craft-input")
                || isManagedOrMalformed(inventory.getResult(), "vanilla-craft-result")) {
            inventory.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        CraftingInventory inventory = event.getInventory();
        if (!containsManagedOrMalformed(inventory.getMatrix(), "vanilla-craft-input")
                && !isManagedOrMalformed(event.getCurrentItem(), "vanilla-craft-result")) {
            return;
        }

        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            player.sendMessage(MANAGED_RECIPE_MESSAGE);
        }
    }

    private boolean containsManagedOrMalformed(ItemStack[] stacks, String sourcePrefix) {
        if (stacks == null) {
            return false;
        }
        for (int slot = 0; slot < stacks.length; slot++) {
            if (isManagedOrMalformed(stacks[slot], sourcePrefix + '[' + slot + ']')) {
                return true;
            }
        }
        return false;
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
                    "Blocked malformed managed-item identity at vanilla crafting boundary " + source,
                    exception
            );
            return true;
        }
    }
}
