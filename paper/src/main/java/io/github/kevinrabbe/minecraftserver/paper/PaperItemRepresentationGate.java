package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationIssue;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

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

    PaperItemRepresentationGate(
            MinecraftServerPlugin plugin,
            PaperPlayerItemRepresentationValidator validator
    ) {
        this.plugin = plugin;
        this.validator = validator;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        try {
            List<ItemRepresentationIssue> issues = validator.validate(player);
            if (issues.isEmpty()) {
                return;
            }

            plugin.getLogger().severe(() -> formatIncident(player, issues));
            player.kick(QUARANTINED_MESSAGE);
        } catch (PaperItemRepresentationException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Malformed custom item identity metadata for player " + player.getUniqueId(),
                    exception
            );
            player.kick(QUARANTINED_MESSAGE);
        } catch (SQLException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not validate custom item authority for player " + player.getUniqueId(),
                    exception
            );
            player.kick(VALIDATION_UNAVAILABLE_MESSAGE);
        }
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
