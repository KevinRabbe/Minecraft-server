package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pvp.RankedArenaException;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedArenaRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedLeaderboardEntry;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedLeaderboardRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedMatchSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedMatchmakingRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedRatingSnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Modern-Paper entry surface for explicit opt-in 1v1 Ranked matchmaking and its separate ladder. */
final class PaperRankedCommand implements CommandExecutor, TabCompleter, Listener {
    private final MinecraftServerPlugin plugin;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final RankedMatchmakingRepository matchmaking;
    private final RankedArenaRepository ranked;
    private final RankedLeaderboardRepository leaderboard;
    private final PaperRankedLeaderboardView leaderboardView;

    PaperRankedCommand(
            MinecraftServerPlugin plugin,
            PaperPlayerIdentityResolver playerIdentities,
            RankedMatchmakingRepository matchmaking,
            RankedArenaRepository ranked,
            RankedLeaderboardRepository leaderboard,
            PaperRankedLeaderboardView leaderboardView
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.matchmaking = Objects.requireNonNull(matchmaking, "matchmaking");
        this.ranked = Objects.requireNonNull(ranked, "ranked");
        this.leaderboard = Objects.requireNonNull(leaderboard, "leaderboard");
        this.leaderboardView = Objects.requireNonNull(leaderboardView, "leaderboardView");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use Ranked matchmaking."));
            return true;
        }

        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1) {
            usage(player);
            return true;
        }

        switch (action) {
            case "join" -> scheduleJoin(player.getUniqueId());
            case "leave" -> scheduleLeave(player.getUniqueId(), true);
            case "status" -> scheduleStatus(player.getUniqueId());
            case "top" -> scheduleTop(player.getUniqueId());
            default -> usage(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("status", "join", "leave", "top").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        scheduleLeave(event.getPlayer().getUniqueId(), false);
    }

    private void scheduleJoin(UUID minecraftUuid) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                Optional<RankedMatchSnapshot> match = matchmaking.join(playerId);
                if (match.isEmpty()) {
                    sendIfOnline(minecraftUuid, "Joined the Ranked 1v1 queue. Use /ranked leave to stop waiting.");
                    return;
                }
                notifyMatch(match.orElseThrow());
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not join Ranked matchmaking.", exception);
            }
        });
    }

    private void scheduleLeave(UUID minecraftUuid, boolean notify) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                boolean removed = matchmaking.leave(playerId);
                if (!notify) return;

                if (removed) {
                    sendIfOnline(minecraftUuid, "Left the Ranked queue.");
                    return;
                }
                Optional<RankedMatchSnapshot> liveMatch = matchmaking.liveMatch(playerId);
                if (liveMatch.isPresent()) {
                    sendIfOnline(
                            minecraftUuid,
                            "Your Ranked match already exists; queue leave cannot cancel a live match."
                    );
                } else {
                    sendIfOnline(minecraftUuid, "You are not waiting in the Ranked queue.");
                }
            } catch (SQLException | RuntimeException exception) {
                if (notify) handleFailure(minecraftUuid, "Could not leave Ranked matchmaking.", exception);
                else plugin.getLogger().log(Level.FINE, "Could not clear Ranked queue intent on logout", exception);
            }
        });
    }

    private void scheduleStatus(UUID minecraftUuid) {
        runAsync(() -> {
            try {
                UUID playerId = requirePlayerId(minecraftUuid);
                Optional<RankedMatchSnapshot> liveMatch = matchmaking.liveMatch(playerId);
                Optional<Instant> queuedAt = matchmaking.queuedAt(playerId);
                Optional<RankedRatingSnapshot> rating = ranked.loadRating(playerId);

                if (liveMatch.isPresent()) {
                    RankedMatchSnapshot match = liveMatch.orElseThrow();
                    sendIfOnline(
                            minecraftUuid,
                            "Ranked: " + match.status() + " match " + match.matchId()
                                    + ratingSuffix(rating) + "."
                    );
                } else if (queuedAt.isPresent()) {
                    sendIfOnline(minecraftUuid, "Ranked: waiting in queue" + ratingSuffix(rating) + ".");
                } else {
                    sendIfOnline(minecraftUuid, "Ranked: not queued" + ratingSuffix(rating) + ".");
                }
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not load Ranked status.", exception);
            }
        });
    }

    private void scheduleTop(UUID minecraftUuid) {
        runAsync(() -> {
            try {
                List<RankedLeaderboardEntry> entries = leaderboard.top(PaperRankedLeaderboardView.MAX_ENTRIES);
                leaderboardView.open(minecraftUuid, entries);
            } catch (SQLException | RuntimeException exception) {
                handleFailure(minecraftUuid, "Could not load the Ranked leaderboard.", exception);
            }
        });
    }

    private void notifyMatch(RankedMatchSnapshot match) throws SQLException {
        UUID playerAMinecraftUuid = playerIdentities.resolveMinecraftUuid(match.playerAId()).orElseThrow(
                () -> new RankedArenaException("Ranked participant A has no Minecraft identity")
        );
        UUID playerBMinecraftUuid = playerIdentities.resolveMinecraftUuid(match.playerBId()).orElseThrow(
                () -> new RankedArenaException("Ranked participant B has no Minecraft identity")
        );
        String message = "Ranked match found. Preparing the isolated 1.8.9 arena...";
        sendIfOnline(playerAMinecraftUuid, message);
        sendIfOnline(playerBMinecraftUuid, message);
    }

    private UUID requirePlayerId(UUID minecraftUuid) throws SQLException {
        return playerIdentities.resolve(minecraftUuid).orElseThrow(
                () -> new RankedArenaException("Persistent player identity is not available.")
        );
    }

    private static String ratingSuffix(Optional<RankedRatingSnapshot> rating) {
        return rating.map(snapshot -> "; rating " + snapshot.rating()).orElse("; unrated");
    }

    private void handleFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof RankedArenaException || cause instanceof IllegalArgumentException) {
            String message = cause.getMessage();
            sendIfOnline(minecraftUuid, message == null || message.isBlank() ? fallback : message);
            return;
        }
        plugin.getLogger().log(Level.WARNING, fallback, cause);
        sendIfOnline(minecraftUuid, fallback);
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Ranked work", exception);
        }
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline()) player.sendMessage(Component.text(message));
        });
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static void usage(Player player) {
        player.sendMessage(Component.text("Ranked: /ranked [status] | join | leave | top"));
    }
}
