package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationIssue;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationValidationResult;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

/**
 * Last join-boundary gate, registered after PaperSessionController so it inspects the authoritative loaded payload
 * before PlayerJoinEvent returns control to gameplay.
 */
final class PaperItemRepresentationGate implements Listener {
    private static final int MAX_LOGGED_ISSUES = 8;
    private static final Component QUARANTINED_MESSAGE = Component.text(
            "Your carried item state failed authority validation and has been isolated. Please contact staff."
    );
    private static final Component VALIDATION_UNAVAILABLE_MESSAGE = Component.text(
            "Item authority validation is temporarily unavailable. Please reconnect shortly."
    );

    private final MinecraftServerPlugin plugin;
    private final PaperPlayerItemRepresentationValidator validator;
    private final PaperItemRuntimeMaterializer materializer;
    private final PaperItemRuntimePresentation presentation;

    PaperItemRepresentationGate(
            MinecraftServerPlugin plugin,
            PaperPlayerItemRepresentationValidator validator
    ) {
        this.plugin = plugin;
        this.validator = validator;
        this.materializer = new PaperItemRuntimeMaterializer(plugin, plugin.itemCatalog());
        this.presentation = new PaperItemRuntimePresentation(plugin, plugin.itemCatalog());
        plugin.getServer().getPluginManager().registerEvents(new PaperManagedItemWorldCustodyGate(plugin), plugin);
        plugin.getServer().getPluginManager().registerEvents(new PaperManagedItemContainerCustodyGate(plugin), plugin);
        plugin.getServer().getPluginManager().registerEvents(new PaperManagedItemDisplayCustodyGate(plugin), plugin);
        plugin.getServer().getPluginManager().registerEvents(new PaperManagedItemBlockHolderCustodyGate(plugin), plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new PaperManagedItemNestedBlockPlacementGate(plugin),
                plugin
        );
        plugin.getServer().getPluginManager().registerEvents(new PaperManagedItemCreativeCustodyGate(plugin), plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        try {
            ItemRepresentationValidationResult result = validator.validateAndSnapshot(player);
            List<ItemRepresentationIssue> issues = result.issues();
            if (issues.isEmpty()) {
                PaperItemRuntimeStatCache.replaceNow(
                        player.getUniqueId(),
                        result.validatedIndividualSnapshots()
                );
                // Gameplay-relevant derived attributes are part of this hard authority gate, not best-effort lore.
                materializer.refresh(player);
                refreshPresentationBestEffort(player);
                return;
            }

            PaperItemRuntimeStatCache.clear(player.getUniqueId());
            plugin.getLogger().severe(() -> formatIncident(player, issues));
            player.kick(QUARANTINED_MESSAGE);
        } catch (PaperItemRepresentationException exception) {
            quarantineMalformed(player, exception);
        } catch (SQLException exception) {
            PaperItemRuntimeStatCache.clear(player.getUniqueId());
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not validate custom item authority for player " + player.getUniqueId(),
                    exception
            );
            player.kick(VALIDATION_UNAVAILABLE_MESSAGE);
        } catch (RuntimeException exception) {
            quarantineMalformed(player, exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        PaperItemRuntimeStatCache.clear(event.getPlayer().getUniqueId());
    }

    private void refreshPresentationBestEffort(Player player) {
        try {
            presentation.refresh(player);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not refresh derived managed-item presentation for player " + player.getUniqueId(),
                    exception
            );
        }
    }

    private void quarantineMalformed(Player player, RuntimeException exception) {
        PaperItemRuntimeStatCache.clear(player.getUniqueId());
        plugin.getLogger().log(
                Level.SEVERE,
                "Malformed or unreadable custom item/runtime state for player " + player.getUniqueId(),
                exception
        );
        player.kick(QUARANTINED_MESSAGE);
    }

    private static String formatIncident(Player player, List<ItemRepresentationIssue> issues) {
        StringBuilder message = new StringBuilder(256)
                .append("Rejected custom item representations for player ")
                .append(player.getUniqueId())
                .append("; issues=")
                .append(issues.size());

        int limit = Math.min(MAX_LOGGED_ISSUES, issues.size());
        for (int index = 0; index < limit; index++) {
            ItemRepresentationIssue issue = issues.get(index);
            message.append(" | ")
                    .append(issue.source())
                    .append(':')
                    .append(issue.code())
                    .append(':')
                    .append(issue.detail());
        }
        if (issues.size() > limit) {
            message.append(" | ...");
        }
        return message.toString();
    }
}
