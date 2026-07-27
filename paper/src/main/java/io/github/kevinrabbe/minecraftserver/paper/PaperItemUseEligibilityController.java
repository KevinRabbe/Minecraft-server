package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Maintains the disposable Paper projection used by future item use/equip checks.
 *
 * <p>This listener does not block any action. It only gives a real skill-gated catalog a fresh bounded projection after
 * the persistent join lifecycle has completed, invalidates that projection on detach, and keeps reconnect races from
 * reviving an old refresh as current. An unrestricted catalog performs no identity/progression query at all.</p>
 */
final class PaperItemUseEligibilityController implements Listener {
    private static final long INITIAL_REFRESH_DELAY_TICKS = 1L;
    private static final long RETRY_DELAY_TICKS = 20L;
    private static final int MAX_REFRESH_ATTEMPTS = 3;

    private final JavaPlugin plugin;
    private final PaperPlayerIdentityResolver identities;
    private final PaperItemUseEligibilityCache cache;
    private final AtomicLong refreshSequence = new AtomicLong();
    private final ConcurrentHashMap<UUID, Long> activeTokenByMinecraftUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> playerIdByMinecraftUuid = new ConcurrentHashMap<>();

    PaperItemUseEligibilityController(
            JavaPlugin plugin,
            PaperPlayerIdentityResolver identities,
            PaperItemUseEligibilityCache cache
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!cache.requiresProgressionSnapshot()) {
            return;
        }
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        runOnMainThreadLater(() -> beginRefresh(minecraftUuid, 1), INITIAL_REFRESH_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        activeTokenByMinecraftUuid.remove(minecraftUuid);
        UUID playerId = playerIdByMinecraftUuid.remove(minecraftUuid);
        if (playerId != null) {
            cache.invalidate(playerId);
        }
    }

    private void beginRefresh(UUID minecraftUuid, int attempt) {
        if (!plugin.isEnabled() || !cache.requiresProgressionSnapshot()) {
            return;
        }
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        long token = refreshSequence.incrementAndGet();
        activeTokenByMinecraftUuid.put(minecraftUuid, token);
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> refreshOffThread(minecraftUuid, token, attempt)
            );
        } catch (RejectedExecutionException | IllegalStateException exception) {
            if (activeTokenByMinecraftUuid.remove(minecraftUuid, token)) {
                plugin.getLogger().log(Level.WARNING, "Could not schedule item-use eligibility refresh", exception);
                scheduleRetry(minecraftUuid, attempt);
            }
        }
    }

    private void refreshOffThread(UUID minecraftUuid, long token, int attempt) {
        UUID playerId = null;
        try {
            Optional<UUID> resolved = identities.resolve(minecraftUuid);
            if (resolved.isEmpty()) {
                throw new IllegalStateException("Online player has no persistent player identity");
            }
            playerId = resolved.orElseThrow();

            if (!isCurrent(minecraftUuid, token)) {
                return;
            }

            UUID previousPlayerId = playerIdByMinecraftUuid.put(minecraftUuid, playerId);
            if (previousPlayerId != null && !previousPlayerId.equals(playerId)) {
                cache.invalidate(previousPlayerId);
            }

            // Every attachment starts from a missing/fail-closed projection until this fresh authority read completes.
            cache.invalidate(playerId);
            cache.refresh(playerId);

            UUID completedPlayerId = playerId;
            runOnMainThread(() -> completeSuccess(minecraftUuid, completedPlayerId, token));
        } catch (SQLException | RuntimeException exception) {
            UUID failedPlayerId = playerId;
            runOnMainThread(() -> completeFailure(minecraftUuid, failedPlayerId, token, attempt, exception));
        }
    }

    private void completeSuccess(UUID minecraftUuid, UUID playerId, long token) {
        Long current = activeTokenByMinecraftUuid.get(minecraftUuid);
        if (current == null) {
            // Quit occurred after the worker's last token check; no live attachment may retain the completed projection.
            playerIdByMinecraftUuid.remove(minecraftUuid, playerId);
            cache.invalidate(playerId);
            return;
        }
        if (current.longValue() != token) {
            // A newer attachment/refresh owns lifecycle state. The cache merge is version-fenced and monotonic, so the
            // old read cannot overgrant; the newer refresh is responsible for final freshness.
            return;
        }

        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline()) {
            activeTokenByMinecraftUuid.remove(minecraftUuid, token);
            playerIdByMinecraftUuid.remove(minecraftUuid, playerId);
            cache.invalidate(playerId);
        }
    }

    private void completeFailure(
            UUID minecraftUuid,
            UUID playerId,
            long token,
            int attempt,
            Throwable failure
    ) {
        Long current = activeTokenByMinecraftUuid.get(minecraftUuid);
        if (current == null) {
            if (playerId != null) {
                playerIdByMinecraftUuid.remove(minecraftUuid, playerId);
                cache.invalidate(playerId);
            }
            return;
        }
        if (current.longValue() != token) {
            return;
        }

        activeTokenByMinecraftUuid.remove(minecraftUuid, token);
        if (playerId != null) {
            playerIdByMinecraftUuid.remove(minecraftUuid, playerId);
            cache.invalidate(playerId);
        }
        plugin.getLogger().log(
                Level.WARNING,
                "Item-use eligibility projection refresh failed closed for player " + minecraftUuid
                        + " (attempt " + attempt + "/" + MAX_REFRESH_ATTEMPTS + ")",
                failure
        );
        scheduleRetry(minecraftUuid, attempt);
    }

    private void scheduleRetry(UUID minecraftUuid, int completedAttempt) {
        if (completedAttempt >= MAX_REFRESH_ATTEMPTS || !plugin.isEnabled()) {
            return;
        }
        runOnMainThreadLater(() -> beginRefresh(minecraftUuid, completedAttempt + 1), RETRY_DELAY_TICKS);
    }

    private boolean isCurrent(UUID minecraftUuid, long token) {
        Long current = activeTokenByMinecraftUuid.get(minecraftUuid);
        return current != null && current.longValue() == token;
    }

    private void runOnMainThread(Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private void runOnMainThreadLater(Runnable task, long delayTicks) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }
}
