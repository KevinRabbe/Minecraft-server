package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pvp.RankedLeaderboardEntry;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only anywhere UI for the separate 1.8.9 Ranked leaderboard. */
final class PaperRankedLeaderboardView implements Listener {
    static final int MAX_ENTRIES = 45;
    private static final int INVENTORY_SIZE = 54;

    private final MinecraftServerPlugin plugin;

    PaperRankedLeaderboardView(MinecraftServerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    void open(UUID minecraftUuid, List<RankedLeaderboardEntry> entries) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("entries must contain at most " + MAX_ENTRIES + " rows");
        }
        if (!plugin.isEnabled()) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) return;

            RankedLeaderboardHolder holder = new RankedLeaderboardHolder();
            Inventory inventory = plugin.getServer().createInventory(
                    holder,
                    INVENTORY_SIZE,
                    Component.text("Ranked 1v1 — Top " + MAX_ENTRIES)
            );
            holder.attach(inventory);

            for (int index = 0; index < entries.size(); index++) {
                inventory.setItem(index, entryItem(entries.get(index)));
            }
            inventory.setItem(49, rulesetItem(entries));
            player.openInventory(inventory);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (isRankedLeaderboard(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isRankedLeaderboard(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    private static boolean isRankedLeaderboard(Inventory inventory) {
        return inventory.getHolder() instanceof RankedLeaderboardHolder;
    }

    private static ItemStack entryItem(RankedLeaderboardEntry entry) {
        ItemStack item = new ItemStack(materialForPosition(entry.position()));
        ItemMeta meta = item.getItemMeta();
        meta.customName(Component.text(
                "#" + entry.position() + " " + entry.playerName() + " — " + entry.rating()
        ));
        meta.lore(List.of(
                Component.text("Rating: " + entry.rating()),
                Component.text("Peak: " + entry.peakRating()),
                Component.text("Record: " + entry.wins() + "W - " + entry.losses() + "L"),
                Component.text("Matches: " + entry.matchesPlayed()),
                Component.text("Ruleset: " + entry.rulesetId() + "@" + entry.rulesetVersion())
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack rulesetItem(List<RankedLeaderboardEntry> entries) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.customName(Component.text("Ranked ladder"));
        if (entries.isEmpty()) {
            meta.lore(List.of(
                    Component.text("No completed Ranked matches yet."),
                    Component.text("This ladder is separate from MMO/PvE rankings.")
            ));
        } else {
            RankedLeaderboardEntry first = entries.getFirst();
            meta.lore(List.of(
                    Component.text("Ruleset: " + first.rulesetId() + "@" + first.rulesetVersion()),
                    Component.text("Rating policy: v" + first.ratingPolicyVersion()),
                    Component.text("This ladder is separate from MMO/PvE rankings.")
            ));
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Material materialForPosition(int position) {
        return switch (position) {
            case 1 -> Material.NETHER_STAR;
            case 2 -> Material.DIAMOND;
            case 3 -> Material.GOLD_INGOT;
            default -> Material.PAPER;
        };
    }

    private static final class RankedLeaderboardHolder implements InventoryHolder {
        private Inventory inventory;

        private void attach(Inventory inventory) {
            if (this.inventory != null) {
                throw new IllegalStateException("Ranked leaderboard inventory is already attached");
            }
            this.inventory = Objects.requireNonNull(inventory, "inventory");
        }

        @Override
        public Inventory getInventory() {
            if (inventory == null) {
                throw new IllegalStateException("Ranked leaderboard inventory is not attached yet");
            }
            return inventory;
        }
    }
}
