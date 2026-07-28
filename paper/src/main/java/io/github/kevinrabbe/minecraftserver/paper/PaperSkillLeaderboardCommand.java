package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillLeaderboardEntry;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillLeaderboardRepository;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionException;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Bounded player-facing projection of authoritative MMO skill XP rankings. */
final class PaperSkillLeaderboardCommand implements CommandExecutor, TabCompleter {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_CHAT_LIMIT = 20;

    private final JavaPlugin plugin;
    private final SkillLeaderboardRepository leaderboards;
    private final SkillProgressionCatalog catalog;

    PaperSkillLeaderboardCommand(
            JavaPlugin plugin,
            SkillLeaderboardRepository leaderboards,
            SkillProgressionCatalog catalog
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.leaderboards = Objects.requireNonNull(leaderboards, "leaderboards");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use the MMO leaderboard command."));
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            usage(player);
            return true;
        }

        try {
            SkillId skillId = new SkillId(args[0].toLowerCase(Locale.ROOT));
            catalog.require(skillId);
            int limit = args.length == 2 ? parseLimit(args[1]) : DEFAULT_LIMIT;
            schedule(player.getUniqueId(), skillId, limit);
        } catch (IllegalArgumentException | SkillProgressionException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Invalid leaderboard request.")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return catalog.all().stream()
                    .map(definition -> definition.skillId().value())
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private void schedule(UUID minecraftUuid, SkillId skillId, int limit) {
        runAsync(() -> {
            try {
                List<SkillLeaderboardEntry> entries = leaderboards.top(skillId, limit);
                ArrayList<String> messages = new ArrayList<>();
                if (entries.isEmpty()) {
                    messages.add("No ranked " + skillId.value() + " players yet.");
                } else {
                    messages.add(
                            skillId.value() + " leaderboard — top " + entries.size()
                                    + " — active cap " + entries.getFirst().activeCap()
                    );
                    for (SkillLeaderboardEntry entry : entries) {
                        messages.add(
                                "#" + entry.rank() + " " + entry.playerName()
                                        + " — Lv " + entry.level() + "/" + entry.activeCap()
                                        + " — " + entry.experience() + " XP"
                        );
                    }
                }
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not load skill leaderboard", exception);
                sendIfOnline(minecraftUuid, "Could not load skill leaderboard.");
            }
        });
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule skill leaderboard query", exception);
        }
    }

    private void sendMessagesIfOnline(UUID minecraftUuid, List<String> messages) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) return;
            messages.forEach(message -> player.sendMessage(Component.text(message)));
        });
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        sendMessagesIfOnline(minecraftUuid, List.of(message));
    }

    private void usage(Player player) {
        player.sendMessage(Component.text("Leaderboard: /leaderboard <skill> [1-" + MAX_CHAT_LIMIT + "]"));
        player.sendMessage(Component.text(
                "Skills: " + String.join(
                        ", ",
                        catalog.all().stream().map(definition -> definition.skillId().value()).toList()
                )
        ));
    }

    private static int parseLimit(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > MAX_CHAT_LIMIT) {
                throw new IllegalArgumentException("leaderboard limit must be between 1 and " + MAX_CHAT_LIMIT);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("leaderboard limit must be a whole number", exception);
        }
    }

    private static String playerMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
