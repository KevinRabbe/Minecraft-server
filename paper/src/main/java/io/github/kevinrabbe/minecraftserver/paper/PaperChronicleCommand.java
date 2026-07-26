package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.history.ChronicleEvent;
import io.github.kevinrabbe.minecraftserver.common.history.ChronicleRepository;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Bounded read-only presentation of authoritative world history. */
final class PaperChronicleCommand implements CommandExecutor {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_CHAT_LIMIT = 20;

    private final JavaPlugin plugin;
    private final ChronicleRepository chronicle;

    private PaperChronicleCommand(JavaPlugin plugin, ChronicleRepository chronicle) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.chronicle = Objects.requireNonNull(chronicle, "chronicle");
    }

    static void install(JavaPlugin plugin, DataSource dataSource) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(dataSource, "dataSource");
        PluginCommand command = Objects.requireNonNull(
                plugin.getCommand("chronicle"),
                "chronicle command missing from plugin.yml"
        );
        command.setExecutor(new PaperChronicleCommand(plugin, new ChronicleRepository(dataSource)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
                List<ChronicleEvent> events = chronicle.listRecent(limit);
                ArrayList<String> messages = new ArrayList<>();
                if (events.isEmpty()) {
                    messages.add("Chronicle — no canonical history recorded yet.");
                } else {
                    messages.add("Chronicle — latest " + events.size() + " event" + (events.size() == 1 ? "" : "s"));
                    events.forEach(event -> messages.add(format(event)));
                }
                sendMessages(sender, messages);
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not load Chronicle history", exception);
                sendMessages(sender, List.of("Could not load Chronicle history."));
            }
        });
        return true;
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Chronicle query", exception);
        }
    }

    private void sendMessages(CommandSender sender, List<String> messages) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(
                plugin,
                () -> messages.forEach(message -> sender.sendMessage(Component.text(message)))
        );
    }

    private static String format(ChronicleEvent event) {
        StringBuilder line = new StringBuilder()
                .append(event.occurredAt())
                .append(" — ")
                .append(humanize(event.eventType()));
        if (event.worldEraId() != null) {
            line.append(" · era ").append(event.worldEraId().value());
        }
        line.append(" · ").append(event.sourceKind()).append('/').append(event.sourceId());
        if (!event.metadata().isEmpty()) {
            line.append(" · ");
            boolean first = true;
            for (Map.Entry<String, String> entry : event.metadata().entrySet()) {
                if (!first) line.append(", ");
                first = false;
                line.append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        return line.toString();
    }

    private static String humanize(String eventType) {
        String[] words = eventType.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static int parseLimit(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > MAX_CHAT_LIMIT) {
                throw new IllegalArgumentException("Chronicle limit must be between 1 and " + MAX_CHAT_LIMIT);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Chronicle limit must be a whole number", exception);
        }
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage(Component.text("Chronicle: /chronicle [1-" + MAX_CHAT_LIMIT + "]"));
    }
}
