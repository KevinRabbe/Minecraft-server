package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapLeaderboardRepository;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Preserves the original skill command while adding explicit namespaced Persistent-MMO Map boards. */
final class PaperLeaderboardRouterCommand implements CommandExecutor, TabCompleter {
    private final PaperSkillLeaderboardCommand skills;
    private final PaperMapLeaderboardCommand maps;

    PaperLeaderboardRouterCommand(PaperSkillLeaderboardCommand skills, PaperMapLeaderboardCommand maps) {
        this.skills = Objects.requireNonNull(skills, "skills");
        this.maps = Objects.requireNonNull(maps, "maps");
    }

    /**
     * The base skill leaderboard is registered near the end of plugin enable, after {@link PaperMapRuntime} is built.
     * Queue this augmentation for the first server tick so it wraps that already-installed command instead of racing
     * plugin bootstrap or duplicating the command declaration.
     */
    static void scheduleInstall(JavaPlugin plugin, DataSource dataSource) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(dataSource, "dataSource");
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PluginCommand command = plugin.getCommand("leaderboard");
            if (command == null) {
                plugin.getLogger().severe("Could not install Map leaderboard router: leaderboard command is missing");
                return;
            }
            if (command.getExecutor() instanceof PaperLeaderboardRouterCommand) {
                return;
            }
            if (!(command.getExecutor() instanceof PaperSkillLeaderboardCommand skills)) {
                plugin.getLogger().severe(
                        "Could not install Map leaderboard router: leaderboard executor is not the MMO skill command"
                );
                return;
            }
            PaperLeaderboardRouterCommand router = new PaperLeaderboardRouterCommand(
                    skills,
                    new PaperMapLeaderboardCommand(plugin, new MapLeaderboardRepository(dataSource))
            );
            command.setExecutor(router);
            command.setTabCompleter(router);
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("map")) {
            return maps.onCommand(sender, command, label, tail(args));
        }
        return skills.onCommand(sender, command, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            LinkedHashSet<String> result = new LinkedHashSet<>();
            if ("map".startsWith(prefix)) {
                result.add("map");
            }
            result.addAll(skills.onTabComplete(sender, command, alias, args));
            return List.copyOf(result);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("map")) {
            return maps.onTabComplete(sender, command, alias, tail(args));
        }
        return skills.onTabComplete(sender, command, alias, args);
    }

    private static String[] tail(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        return Arrays.copyOfRange(args, 1, args.length);
    }
}
