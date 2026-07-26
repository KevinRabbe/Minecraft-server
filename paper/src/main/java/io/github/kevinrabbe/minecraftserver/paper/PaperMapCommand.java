package io.github.kevinrabbe.minecraftserver.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/** Minimal player-facing boundary for consuming the authoritative Map in the main hand and entering its reserved run. */
final class PaperMapCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final PaperMapOpenService maps;

    private PaperMapCommand(JavaPlugin plugin, PaperMapOpenService maps) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.maps = Objects.requireNonNull(maps, "maps");
    }

    static void install(JavaPlugin plugin, PaperMapOpenService maps) {
        PluginCommand command = Objects.requireNonNull(plugin.getCommand("map"), "map command missing from plugin.yml");
        PaperMapCommand executor = new PaperMapCommand(plugin, maps);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can open a Map."));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("open")) {
            usage(player);
            return true;
        }

        maps.open(player, player.getInventory().getItemInMainHand()).whenComplete((opened, failure) -> {
            if (failure == null) {
                sendIfOnline(player, "Map opened. Entering reserved encounter " + opened.runId() + ".");
                return;
            }
            Throwable cause = unwrap(failure);
            if (!(cause instanceof IllegalArgumentException
                    || cause instanceof PaperItemRepresentationException
                    || cause instanceof io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException)) {
                plugin.getLogger().log(Level.WARNING, "Could not open Map", cause);
            }
            sendIfOnline(player, playerMessage(cause, "Could not open that Map safely."));
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && "open".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("open");
        }
        return List.of();
    }

    private void sendIfOnline(Player player, String message) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(Component.text(message));
            }
        });
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String playerMessage(Throwable failure, String fallback) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static void usage(Player player) {
        player.sendMessage(Component.text("Map: /map open — opens the authoritative Map in your main hand."));
    }
}
