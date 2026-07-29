package io.github.kevinrabbe.minecraftserver.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Keeps network-managed ItemStacks out of vanilla inventory containers that have no persistent network custody model.
 *
 * <p>Movement inside the player's own inventory remains ordinary Minecraft behavior. External container, processing,
 * merchant, anvil, enchanting and similar inventory surfaces remain closed for managed items until a feature-specific
 * adapter explicitly owns the corresponding value transition.</p>
 */
final class PaperManagedItemContainerCustodyGate implements Listener {
    private static final Component CONTAINER_DENIED = Component.text(
            "Managed items cannot be placed in vanilla containers or processing inventories."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperItemIdentityCodec identityCodec;

    PaperManagedItemContainerCustodyGate(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identityCodec = new PaperItemIdentityCodec(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !hasExternalTopInventory(event.getView())) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();

        // A managed cursor can be placed/swapped/collected through many click actions. While an external inventory is
        // open, keep that cursor inside player custody by rejecting the interaction instead of attempting to infer every
        // client-side click variant.
        if (isManagedOrMalformed(event.getCursor(), "container-cursor")) {
            deny(event, player);
            return;
        }

        if (clicked == top) {
            if (isManagedOrMalformed(event.getCurrentItem(), "container-top-slot")) {
                deny(event, player);
                return;
            }
            if (event.getClick() == ClickType.NUMBER_KEY) {
                int hotbarButton = event.getHotbarButton();
                if (hotbarButton >= 0
                        && isManagedOrMalformed(player.getInventory().getItem(hotbarButton), "container-hotbar-swap")) {
                    deny(event, player);
                    return;
                }
            }
            if (event.getClick() == ClickType.SWAP_OFFHAND
                    && isManagedOrMalformed(player.getInventory().getItemInOffHand(), "container-offhand-swap")) {
                deny(event, player);
            }
            return;
        }

        if (clicked == event.getView().getBottomInventory()
                && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                && isManagedOrMalformed(event.getCurrentItem(), "container-shift-transfer")) {
            deny(event, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !hasExternalTopInventory(event.getView())) {
            return;
        }
        if (!isManagedOrMalformed(event.getOldCursor(), "container-drag-cursor")) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(rawSlot -> rawSlot >= 0 && rawSlot < topSize)) {
            event.setCancelled(true);
            player.sendMessage(CONTAINER_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAutomatedInventoryMove(InventoryMoveItemEvent event) {
        if (isManagedOrMalformed(event.getItem(), "automated-inventory-move")) {
            event.setCancelled(true);
        }
    }

    private void deny(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        player.sendMessage(CONTAINER_DENIED);
    }

    private static boolean hasExternalTopInventory(InventoryView view) {
        InventoryType type = view.getTopInventory().getType();
        return type != InventoryType.CRAFTING && type != InventoryType.PLAYER;
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
                    "Blocked malformed managed-item identity at container-custody boundary " + source,
                    exception
            );
            return true;
        }
    }
}
