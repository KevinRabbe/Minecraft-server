package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.verification.IntegrityIssue;
import io.github.kevinrabbe.minecraftserver.common.verification.PersistentIntegrityVerifier;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Explicit bounded operator diagnostic over persistent economy/custody, item/progression, PvE, clan, and competitive evidence. */
final class PaperIntegrityCommand implements CommandExecutor {
    static final String PERMISSION = "minecraftserver.admin.integrity";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_COMMAND_LIMIT = 100;

    private final JavaPlugin plugin;
    private final PersistentIntegrityVerifier verifier;

    private PaperIntegrityCommand(JavaPlugin plugin, PersistentIntegrityVerifier verifier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    /** Compatibility installer for environments that do not expose loaded content catalogs. */
    static void install(JavaPlugin plugin, DataSource dataSource) {
        install(plugin, new PersistentIntegrityVerifier(dataSource));
    }

    /** Compatibility installer with item-definition-aware invariants only. */
    static void install(JavaPlugin plugin, DataSource dataSource, ItemCatalog itemCatalog) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        install(plugin, new PersistentIntegrityVerifier(dataSource, itemCatalog));
    }

    /** Production installer: includes catalog-aware item and staged-skill progression reconciliation. */
    static void install(
            JavaPlugin plugin,
            DataSource dataSource,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        Objects.requireNonNull(skillCatalog, "skillCatalog");
        install(plugin, new PersistentIntegrityVerifier(dataSource, itemCatalog, skillCatalog));
    }

    private static void install(JavaPlugin plugin, PersistentIntegrityVerifier verifier) {
        Objects.requireNonNull(plugin, "plugin");
        PluginCommand command = Objects.requireNonNull(
                plugin.getCommand("integrity"),
                "integrity command missing from plugin.yml"
        );
        if (!PERMISSION.equals(command.getPermission())) {
            throw new IllegalStateException(
                    "integrity command must require capability " + PERMISSION + " in plugin.yml"
            );
        }
        command.setExecutor(new PaperIntegrityCommand(plugin, verifier));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission to run persistent integrity diagnostics."));
            return true;
        }

        final int limit;
        try {
            if (args.length > 1) {
                usage(sender);
                return true;
            }
            limit = args.length == 0 ? DEFAULT_LIMIT : parseLimit(args[0]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Component.text(exception.getMessage()));
            return true;
        }

        runAsync(() -> {
            try {
                List<IntegrityIssue> issues = verifier.verify(limit);
                ArrayList<String> messages = new ArrayList<>();
                if (issues.isEmpty()) {
                    messages.add("Persistent integrity: no issues found in the bounded verification pass.");
                } else {
                    messages.add("Persistent integrity: " + issues.size() + " issue"
                            + (issues.size() == 1 ? "" : "s") + " returned (limit " + limit + ").");
                    for (IntegrityIssue issue : issues) {
                        messages.add(issue.severity() + " · " + issue.code() + " · " + issue.subjectId()
                                + " · " + issue.message());
                    }
                }
                sendMessages(sender, messages);
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Persistent integrity verification failed", exception);
                sendMessages(sender, List.of("Persistent integrity verification failed; see server log."));
            }
        });
        return true;
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule persistent integrity verification", exception);
        }
    }

    private void sendMessages(CommandSender sender, List<String> messages) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(
                plugin,
                () -> messages.forEach(message -> sender.sendMessage(Component.text(message)))
        );
    }

    private static int parseLimit(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > MAX_COMMAND_LIMIT) {
                throw new IllegalArgumentException("Integrity limit must be between 1 and " + MAX_COMMAND_LIMIT);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Integrity limit must be a whole number", exception);
        }
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage(Component.text("Integrity: /integrity [1-" + MAX_COMMAND_LIMIT + "]"));
    }
}
