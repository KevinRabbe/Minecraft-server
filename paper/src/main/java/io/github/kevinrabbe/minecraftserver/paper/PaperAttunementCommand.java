package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementException;
import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementProfileCatalog;
import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementProfileDefinition;
import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementRepository;
import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementSnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Minimal player-facing surface for the one-active-profile Attunement authority. */
final class PaperAttunementCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final AttunementRepository attunement;
    private final AttunementProfileCatalog profiles;

    PaperAttunementCommand(
            JavaPlugin plugin,
            PaperPlayerIdentityResolver playerIdentities,
            AttunementRepository attunement,
            AttunementProfileCatalog profiles
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.attunement = Objects.requireNonNull(attunement, "attunement");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can use Attunement."));
            return true;
        }
        if (args.length > 1) {
            player.sendMessage(Component.text("Usage: /attune [profile]"));
            return true;
        }

        String requestedProfile = args.length == 0 ? null : args[0].trim().toLowerCase(Locale.ROOT);
        if (requestedProfile != null) {
            try {
                profiles.require(requestedProfile);
            } catch (AttunementException exception) {
                player.sendMessage(Component.text(
                        "Unknown Attunement profile. Available: " + availableProfiles()
                ));
                return true;
            }
        }

        UUID minecraftUuid = player.getUniqueId();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> execute(
                    minecraftUuid,
                    requestedProfile
            ));
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Attunement command", exception);
            player.sendMessage(Component.text("Attunement service is temporarily unavailable."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return profiles.all().stream()
                .map(AttunementProfileDefinition::profileId)
                .filter(value -> value.startsWith(prefix))
                .sorted()
                .toList();
    }

    private void execute(UUID minecraftUuid, String requestedProfile) {
        try {
            Optional<UUID> playerId = playerIdentities.resolve(minecraftUuid);
            if (playerId.isEmpty()) {
                sendIfOnline(minecraftUuid, "Attunement service could not resolve your persistent player identity.");
                return;
            }
            AttunementSnapshot snapshot = requestedProfile == null
                    ? attunement.loadOrInitialize(playerId.orElseThrow())
                    : attunement.setActiveProfile(UUID.randomUUID(), playerId.orElseThrow(), requestedProfile);
            sendIfOnline(minecraftUuid, describe(snapshot));
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Attunement persistence failed", exception);
            sendIfOnline(minecraftUuid, "Attunement service is temporarily unavailable.");
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Attunement command failed closed", exception);
            sendIfOnline(minecraftUuid, "Attunement service is temporarily unavailable.");
        }
    }

    private String describe(AttunementSnapshot snapshot) {
        if (snapshot.activeProfileId() == null) {
            return "Attunement: none selected. Points: " + snapshot.totalPoints()
                    + ". Available: " + availableProfiles();
        }
        AttunementProfileDefinition profile = profiles.require(snapshot.activeProfileId());
        return "Attunement: " + title(profile.profileId()) + " → " + title(profile.statKey())
                + ". Points: " + snapshot.totalPoints();
    }

    private String availableProfiles() {
        return profiles.all().stream()
                .map(AttunementProfileDefinition::profileId)
                .sorted()
                .map(PaperAttunementCommand::title)
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private static String title(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(message));
            }
        });
    }
}
