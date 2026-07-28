package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarHistoryEntry;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarHistoryRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLeaderboardEntry;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLeaderboardRepository;
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

/** Player-facing read-only ladder/history for the isolated 1.8.9 Clan-War category. */
final class PaperClanWarLeaderboardCommand implements CommandExecutor, TabCompleter {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_CHAT_LIMIT = 20;

    private final JavaPlugin plugin;
    private final ClanWarLeaderboardRepository leaderboards;
    private final ClanWarHistoryRepository history;

    PaperClanWarLeaderboardCommand(
            JavaPlugin plugin,
            ClanWarLeaderboardRepository leaderboards,
            ClanWarHistoryRepository history
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.leaderboards = Objects.requireNonNull(leaderboards, "leaderboards");
        this.history = Objects.requireNonNull(history, "history");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use the Clan-War leaderboard command."));
            return true;
        }

        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("top")) {
                if (args.length > 2) {
                    usage(player);
                    return true;
                }
                int limit = args.length == 2 ? parseLimit(args[1]) : DEFAULT_LIMIT;
                scheduleTop(player.getUniqueId(), limit);
                return true;
            }
            if (args[0].equalsIgnoreCase("history")) {
                if (args.length > 2) {
                    usage(player);
                    return true;
                }
                int limit = args.length == 2 ? parseLimit(args[1]) : DEFAULT_LIMIT;
                scheduleHistory(player.getUniqueId(), limit);
                return true;
            }
            usage(player);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Invalid Clan-War leaderboard request.")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefix(List.of("top", "history"), args[0]);
        }
        return List.of();
    }

    private void scheduleTop(UUID minecraftUuid, int limit) {
        runAsync(() -> {
            try {
                List<ClanWarLeaderboardEntry> entries = leaderboards.top(limit);
                ArrayList<String> messages = new ArrayList<>();
                if (entries.isEmpty()) {
                    messages.add("Clan Wars — Rating — no completed rated wars yet.");
                } else {
                    messages.add("Clan Wars — Rating — top " + entries.size());
                    for (ClanWarLeaderboardEntry entry : entries) {
                        messages.add(
                                "#" + entry.rank() + " [" + entry.clanTag() + "] " + entry.clanName()
                                        + " — " + entry.rating() + " rating"
                                        + " — " + entry.wins() + "W/" + entry.losses() + "L"
                        );
                    }
                }
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                queryFailed(minecraftUuid, "Could not load Clan-War leaderboard.", exception);
            }
        });
    }

    private void scheduleHistory(UUID minecraftUuid, int limit) {
        runAsync(() -> {
            try {
                List<ClanWarHistoryEntry> entries = history.recent(limit);
                ArrayList<String> messages = new ArrayList<>();
                if (entries.isEmpty()) {
                    messages.add("Clan Wars — History — no completed wars yet.");
                } else {
                    messages.add("Clan Wars — History — latest " + entries.size());
                    for (ClanWarHistoryEntry entry : entries) {
                        messages.add(formatHistory(entry));
                    }
                }
                sendMessagesIfOnline(minecraftUuid, messages);
            } catch (SQLException | RuntimeException exception) {
                queryFailed(minecraftUuid, "Could not load Clan-War history.", exception);
            }
        });
    }

    private static String formatHistory(ClanWarHistoryEntry entry) {
        boolean challengerWon = entry.winningClanId().equals(entry.challengerClanId());
        String winnerTag = challengerWon ? entry.challengerTag() : entry.defenderTag();
        String winnerName = challengerWon ? entry.challengerName() : entry.defenderName();
        String loserTag = challengerWon ? entry.defenderTag() : entry.challengerTag();
        String loserName = challengerWon ? entry.defenderName() : entry.challengerName();

        return "[" + winnerTag + "] " + winnerName
                + " defeated [" + loserTag + "] " + loserName
                + " — " + entry.challengerTag() + " " + entry.challengerRatingBefore()
                + "→" + entry.challengerRatingAfter()
                + " / " + entry.defenderTag() + " " + entry.defenderRatingBefore()
                + "→" + entry.defenderRatingAfter()
                + " — " + entry.teamSize() + "v" + entry.teamSize()
                + " — " + entry.rulesetId() + "@" + entry.rulesetVersion()
                + " · rating-policy v" + entry.ratingPolicyVersion()
                + " — " + entry.finishedAt();
    }

    private void queryFailed(UUID minecraftUuid, String message, Throwable exception) {
        plugin.getLogger().log(Level.WARNING, message, exception);
        sendMessagesIfOnline(minecraftUuid, List.of(message));
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Clan-War leaderboard query", exception);
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

    private static List<String> prefix(List<String> values, String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private static void usage(Player player) {
        player.sendMessage(Component.text("Clan-War leaderboards:"));
        player.sendMessage(Component.text("/leaderboard clan-war top [1-" + MAX_CHAT_LIMIT + "]"));
        player.sendMessage(Component.text("/leaderboard clan-war history [1-" + MAX_CHAT_LIMIT + "]"));
    }

    private static String playerMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
