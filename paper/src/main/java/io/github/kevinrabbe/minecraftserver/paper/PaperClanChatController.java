package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanChatDelivery;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanChatDeliveryPage;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanChatRepository;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Bounded best-effort live delivery of authoritative clan-chat transit rows to this Paper backend. */
final class PaperClanChatController implements Listener {
    private static final long POLL_PERIOD_TICKS = 20L;
    private static final int POLL_LIMIT = 100;
    private static final int CLEANUP_EVERY_POLLS = 300;
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final int CLEANUP_LIMIT = 1_000;

    private final JavaPlugin plugin;
    private final String backendId;
    private final ClanChatRepository repository;
    private final AtomicBoolean pollInFlight = new AtomicBoolean();

    private long cursor;
    private int pollsSinceCleanup;
    private BukkitTask pollTask;

    PaperClanChatController(
            JavaPlugin plugin,
            String backendId,
            ClanChatRepository repository,
            long initialSequence
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        this.backendId = backendId.trim();
        this.repository = Objects.requireNonNull(repository, "repository");
        if (initialSequence < 0) {
            throw new IllegalArgumentException("initialSequence must be >= 0");
        }
        this.cursor = initialSequence;
    }

    void start() {
        if (pollTask != null || !plugin.isEnabled()) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        pollTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::poll,
                1L,
                POLL_PERIOD_TICKS
        );
    }

    void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) stop();
    }

    private void poll() {
        if (!plugin.isEnabled() || !pollInFlight.compareAndSet(false, true)) return;
        try {
            ClanChatDeliveryPage page = repository.pollForBackend(backendId, cursor, POLL_LIMIT);
            cursor = page.scannedThroughSequence();
            deliverOnMainThread(page.deliveries());

            pollsSinceCleanup++;
            if (pollsSinceCleanup >= CLEANUP_EVERY_POLLS) {
                repository.deleteExpired(RETENTION, CLEANUP_LIMIT);
                pollsSinceCleanup = 0;
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Clan chat transit poll failed", exception);
        } finally {
            pollInFlight.set(false);
        }
    }

    private void deliverOnMainThread(List<ClanChatDelivery> deliveries) {
        if (deliveries.isEmpty() || !plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled()) return;
                for (ClanChatDelivery delivery : deliveries) {
                    Component rendered = Component.text(
                            "[Clan] " + delivery.message().senderName() + ": " + delivery.message().body()
                    );
                    for (UUID minecraftUuid : delivery.recipientMinecraftUuids()) {
                        Player player = plugin.getServer().getPlayer(minecraftUuid);
                        if (player != null && player.isOnline()) {
                            player.sendMessage(rendered);
                        }
                    }
                }
            });
        } catch (RejectedExecutionException | IllegalStateException exception) {
            if (plugin.isEnabled()) {
                plugin.getLogger().log(Level.FINE, "Could not schedule clan chat delivery", exception);
            }
        }
    }
}
