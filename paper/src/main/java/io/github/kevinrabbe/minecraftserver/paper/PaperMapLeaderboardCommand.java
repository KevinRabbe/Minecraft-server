package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapLeaderboardEntry;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapLeaderboardRepository;
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

/** Player-facing Persistent-MMO Map rankings; never mixes with the 1.8.9 competitive category. */
final class PaperMapLeaderboardCommand implements CommandExecutor, TabCompleter {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_CHAT_LIMIT = 20;

    private final JavaPlugin plugin;
    private final MapLeaderboardRepository leaderboards;

    PaperMapLeaderboardCommand(JavaPlugin plugin, MapLeaderboardRepository leaderboards) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.leaderboards = Objects.requireNonNull(leaderboards, "leaderboards");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use the MMO Map leaderboard command."));
            return true;
        }
        try {
            if (args.length >= 1 && (args[0].equalsIgnoreCase("solo") || args[0].equalsIgnoreCase("group"))) {
                if (args.length > 2) {
                    usage(player);
                    return true;
                }
                boolean solo = args[0].equalsIgnoreCase("solo");
                int limit = args.length == 2 ? parseLimit(args[1]) : DEFAULT_LIMIT;
                scheduleHighest(player.getUniqueId(), solo, limit);
                return true;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("fastest")) {
                if (args.length < 3 || args.length > 4) {
                    usage(player);
                    return true;
                }
                boolean solo = parseMode(args[1]);
                MapDifficulty difficulty = parseDifficulty(args[2]);
                int limit = args.length == 4 ? parseLimit(args[3]) : DEFAULT_LIMIT;
                scheduleFastest(player.getUniqueId(), solo, difficulty, limit);
                return true;
            }
            usage(player);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text(playerMessage(exception, "Invalid Map leaderboard request.")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefix(List.of("solo", "group", "fastest"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("fastest")) {
            return prefix(List.of("solo", "group"), args[1]);
        }
        return List.of();
    }

    private void scheduleHighest(UUID minecraftUuid, boolean solo, int limit) {
        runAsync(() -> {
            try {
                List<MapLeaderboardEntry> entries = leaderboards.highest(solo, limit);
                sendEntries(
                        minecraftUuid,
                        "PvE / Maps — Highest " + modeLabel(solo),
                        entries
                );
            } catch (SQLException | RuntimeException exception) {
                queryFailed(minecraftUuid, exception);
            }
        });
    }

    private void scheduleFastest(UUID minecraftUuid, boolean solo, MapDifficulty difficulty, int limit) {
        runAsync(() -> {
            try {
                List<MapLeaderboardEntry> entries = leaderboards.fastest(solo, difficulty, limit);
                sendEntries(
                        minecraftUuid,
                        "PvE / Maps — Fastest " + modeLabel(solo) + " — Difficulty " + difficulty.value(),
                        entries
                );
            } catch (SQLException | RuntimeException exception) {
                queryFailed(minecraftUuid, exception);
            }
        });
    }

    private void sendEntries(UUID minecraftUuid, String title, List<MapLeaderboardEntry> entries) {
        ArrayList<String> messages = new ArrayList<>();
        if (entries.isEmpty()) {
            messages.add(title + " — no clears yet.");
        } else {
            messages.add(title + " — top " + entries.size());
            for (MapLeaderboardEntry entry : entries) {
                String participants = String.join(
                        " + ",
                        entry.participants().stream().map(value -> value.playerName()).toList()
                );
                String modifiers = "[]".equals(entry.modifierJson()) ? "" : " · mods " + entry.modifierJson();
                messages.add(
                        "#" + entry.rank() + " " + participants
                                + " — D" + entry.difficulty().value()
                                + " — " + formatElapsed(entry.elapsedMillis())
                                + " — " + entry.environmentId() + "/" + entry.enemyFamilyId() + "/" + entry.objectiveId()
                                + modifiers
                                + " · era " + entry.worldEraId()
                                + " · balance v" + entry.balanceVersion()
                );
            }
        }
        sendMessagesIfOnline(minecraftUuid, messages);
    }

    private void queryFailed(UUID minecraftUuid, Throwable exception) {
        plugin.getLogger().log(Level.WARNING, "Could not load Map leaderboard", exception);
        sendMessagesIfOnline(minecraftUuid, List.of("Could not load PvE / Maps leaderboard."));
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Map leaderboard query", exception);
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

    private static boolean parseMode(String raw) {
        if (raw.equalsIgnoreCase("solo")) return true;
        if (raw.equalsIgnoreCase("group")) return false;
        throw new IllegalArgumentException("Map leaderboard mode must be solo or group");
    }

    private static MapDifficulty parseDifficulty(String raw) {
        try {
            return new MapDifficulty(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Map difficulty must be a whole number", exception);
        }
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

    private static String formatElapsed(long elapsedMillis) {
        long minutes = elapsedMillis / 60_000L;
        double seconds = (elapsedMillis % 60_000L) / 1_000.0;
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%d:%06.3f", minutes, seconds);
        }
        return String.format(Locale.ROOT, "%.3fs", seconds);
    }

    private static String modeLabel(boolean solo) {
        return solo ? "Solo" : "Group";
    }

    private static List<String> prefix(List<String> values, String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private static void usage(Player player) {
        player.sendMessage(Component.text("PvE / Maps leaderboards:"));
        player.sendMessage(Component.text("/leaderboard map solo [1-" + MAX_CHAT_LIMIT + "]"));
        player.sendMessage(Component.text("/leaderboard map group [1-" + MAX_CHAT_LIMIT + "]"));
        player.sendMessage(Component.text(
                "/leaderboard map fastest <solo|group> <difficulty> [1-" + MAX_CHAT_LIMIT + "]"
        ));
    }

    private static String playerMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
