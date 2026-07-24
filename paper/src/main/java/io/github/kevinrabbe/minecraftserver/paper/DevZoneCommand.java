package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Temporary acceptance-test entrypoint for the logical zone transfer path. */
final class DevZoneCommand implements CommandExecutor {
    private final PaperSessionController sessions;

    DevZoneCommand(PaperSessionController sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
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
