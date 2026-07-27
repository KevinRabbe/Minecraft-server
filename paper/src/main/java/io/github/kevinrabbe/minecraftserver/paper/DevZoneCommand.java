package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Temporary acceptance-test entrypoint for the logical zone transfer path. Disabled unless explicitly enabled. */
final class DevZoneCommand implements CommandExecutor {
    static final String PERMISSION = "minecraftserver.dev.route";

    private final PaperSessionController sessions;
    private final boolean enabled;

    DevZoneCommand(PaperSessionController sessions) {
        this(
                sessions,
                DevToolsPolicy.enabled(System.getenv(DevToolsPolicy.ENABLE_ENVIRONMENT_VARIABLE))
        );
    }

    DevZoneCommand(PaperSessionController sessions, boolean enabled) {
        this.sessions = sessions;
        this.enabled = enabled;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("You do not have permission to use this development command.");
            return true;
        }
        if (!enabled) {
            sender.sendMessage("Development tools are disabled on this backend.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This development command can only be used by a player.");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage("Usage: /devzone <logical-zone-id>");
            return true;
        }

        try {
            sessions.requestZoneTransfer(player, args[0]);
        } catch (IllegalArgumentException exception) {
            player.sendMessage("Invalid logical zone id.");
        }
        return true;
    }
}
