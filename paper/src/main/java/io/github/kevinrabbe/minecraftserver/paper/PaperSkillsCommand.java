package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressSnapshot;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionRepository;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Read-only player surface over the authoritative skill XP/cap state. */
final class PaperSkillsCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final SkillProgressionRepository skills;
    private final SkillProgressionCatalog catalog;

    PaperSkillsCommand(
            JavaPlugin plugin,
            PaperPlayerIdentityResolver playerIdentities,
            SkillProgressionRepository skills,
            SkillProgressionCatalog catalog
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player has persistent skill progress."));
            return true;
        }
        if (args.length != 0) {
            player.sendMessage(Component.text("Usage: /skills"));
            return true;
        }

        UUID minecraftUuid = player.getUniqueId();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> loadAndSend(minecraftUuid));
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule skill progress lookup", exception);
            player.sendMessage(Component.text("Skill service is temporarily unavailable."));
        }
        return true;
    }

    private void loadAndSend(UUID minecraftUuid) {
        try {
            Optional<UUID> playerId = playerIdentities.resolve(minecraftUuid);
            if (playerId.isEmpty()) {
                sendIfOnline(minecraftUuid, List.of("Skill service could not resolve your persistent player identity."));
                return;
            }

            ArrayList<String> lines = new ArrayList<>();
            lines.add("Skills");
            for (SkillProgressionDefinition definition : catalog.all()) {
                SkillProgressSnapshot progress = skills.load(playerId.orElseThrow(), definition.skillId());
                lines.add(format(definition, progress));
            }
            sendIfOnline(minecraftUuid, lines);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not load authoritative skill progress", exception);
            sendIfOnline(minecraftUuid, List.of("Skill service is temporarily unavailable."));
        }
    }

    private static String format(SkillProgressionDefinition definition, SkillProgressSnapshot progress) {
        String name = title(progress.skillId().value());
        if (progress.level() >= progress.activeCap()) {
            return name + " — Lv " + progress.level() + "/" + progress.activeCap()
                    + " — " + progress.experience() + " XP (cap reached)";
        }
        long nextThreshold = definition.experienceForLevel(progress.level() + 1);
        long remaining = nextThreshold - progress.experience();
        return name + " — Lv " + progress.level() + "/" + progress.activeCap()
                + " — " + progress.experience() + " XP — " + remaining + " to next";
    }

    private void sendIfOnline(UUID minecraftUuid, List<String> lines) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            for (String line : lines) {
                player.sendMessage(Component.text(line));
            }
        });
    }

    private static String title(String value) {
        String normalized = value.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean capitalize = true;
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (Character.isWhitespace(current)) {
                result.append(current);
                capitalize = true;
            } else if (capitalize) {
                result.append(Character.toUpperCase(current));
                capitalize = false;
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
