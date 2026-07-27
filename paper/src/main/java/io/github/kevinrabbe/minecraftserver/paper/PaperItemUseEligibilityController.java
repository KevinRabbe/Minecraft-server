package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillXpAwardResult;
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
 * the persistent join lifecycle has completed, advances attached projections from already-committed XP results,
 * invalidates the projection on detach, and keeps reconnect races from reviving old refresh work as current. An
 * unrestricted catalog performs no identity/progression query at all.</p>
 */
final class PaperItemUseEligibilityController implements Listener {
    private static final long INITIAL_REFRESH_DELAY_TICKS = 1L;
    private static final long RETRY_DELAY_TICKS = 20L;
    private static final int MAX_REFRESH_ATTEMPTS = 3;

    private final JavaPlugin plugin;
    private final PaperPlayerIdentityResolver identities;
    private final PaperItemUseEligibilityCache cache;
    private final AtomicLong lifecycleSequence = new AtomicLong();
    private final AtomicLong refreshSequence = new AtomicLong();
    private final ConcurrentHashMap<UUID, Long> lifecycleEpochByMinecraftUuid = new ConcurrentHashMap<>();
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

    /** Applies only a result that has already committed in progression authority. Safe from async fulfillment paths. */
    void applyCommittedAward(SkillXpAwardResult award) {
        cache.applyCommittedAward(Objects.requireNonNull(award, "award"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!cache.requiresProgressionSnapshot()) {
            return;
        }
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        long lifecycleEpoch = lifecycleSequence.incrementAndGet();
        lifecycleEpochByMinecraftUuid.put(minecraftUuid, lifecycleEpoch);
        runOnMainThreadLater(
                () -> beginRefresh(minecraftUuid, lifecycleEpoch, 1),
                INITIAL_REFRESH_DELAY_TICKS
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        lifecycleEpochByMinecraftUuid.remove(minecraftUuid);
        activeTokenByMinecraftUuid.remove(minecraftUuid);
        UUID playerId = playerIdByMinecraftUuid.remove(minecraftUuid);
        if (playerId != null) {
            cache.invalidate(playerId);
        }
    }

    private void beginRefresh(UUID minecraftUuid, long lifecycleEpoch, int attempt) {
        if (!plugin.isEnabled()
                || !cache.requiresProgressionSnapshot()
                || !isCurrentLifecycle(minecraftUuid, lifecycleEpoch)) {
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
                    () -> refreshOffThread(minecraftUuid, lifecycleEpoch, token, attempt)
            );
        } catch (RejectedExecutionException | IllegalStateException exception) {
            if (activeTokenByMinecraftUuid.remove(minecraftUuid, token)) {
                plugin.getLogger().log(Level.WARNING, "Could not schedule item-use eligibility refresh", exception);
                scheduleRetry(minecraftUuid, lifecycleEpoch, attempt);
            }
        }
    }

    private void refreshOffThread(UUID minecraftUuid, long lifecycleEpoch, long token, int attempt) {
        UUID playerId = null;
        try {
            Optional<UUID> resolved = identities.resolve(minecraftUuid);
            if (resolved.isEmpty()) {
                throw new IllegalStateException("Online player has no persistent player identity");
            }
            playerId = resolved.orElseThrow();

            if (!isCurrentLifecycle(minecraftUuid, lifecycleEpoch) || !isCurrentRefresh(minecraftUuid, token)) {
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
            runOnMainThread(() -> completeSuccess(
                    minecraftUuid,
                    completedPlayerId,
                    lifecycleEpoch,
                    token
            ));
        } catch (SQLException | RuntimeException exception) {
            UUID failedPlayerId = playerId;
            runOnMainThread(() -> completeFailure(
                    minecraftUuid,
                    failedPlayerId,
                    lifecycleEpoch,
                    token,
                    attempt,
                    exception
            ));
        }
    }

    private void completeSuccess(UUID minecraftUuid, UUID playerId, long lifecycleEpoch, long token) {
        if (!isCurrentLifecycle(minecraftUuid, lifecycleEpoch)) {
            if (!lifecycleEpochByMinecraftUuid.containsKey(minecraftUuid)) {
                playerIdByMinecraftUuid.remove(minecraftUuid, playerId);
                cache.invalidate(playerId);
            }
            return;
        }

        Long current = activeTokenByMinecraftUuid.get(minecraftUuid);
        if (current == null) {
            // Quit occurred after the worker's last token check; no live attachment may retain the completed projection.
            playerIdByMinecraftUuid.remove(minecraftUuid, playerId);
            cache.invalidate(playerId);
            return;
        }
        if (current.longValue() != token) {
            // A newer refresh owns this same attachment. The cache merge is version-fenced and monotonic, so the old
            // read cannot overgrant; the newer refresh is responsible for final freshness.
            return;
        }

        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline()) {
            lifecycleEpochByMinecraftUuid.remove(minecraftUuid, lifecycleEpoch);
            activeTokenByMinecraftUuid.remove(minecraftUuid, token);
            playerIdByMinecraftUuid.remove(minecraftUuid, playerId);
            cache.invalidate(playerId);
        }
    }

    private void completeFailure(
            UUID minecraftUuid,
            UUID playerId,
            long lifecycleEpoch,
            long token,
            int attempt,
            Throwable failure
    ) {
        if (!isCurrentLifecycle(minecraftUuid, lifecycleEpoch)) {
            if (!lifecycleEpochByMinecraftUuid.containsKey(minecraftUuid) && playerId != null) {
                playerIdByMinecraftUuid.remove(minecraftUuid, playerId);
                cache.invalidate(playerId);
            }
            return;
        }

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
        scheduleRetry(minecraftUuid, lifecycleEpoch, attempt);
    }

    private void scheduleRetry(UUID minecraftUuid, long lifecycleEpoch, int completedAttempt) {
        if (completedAttempt >= MAX_REFRESH_ATTEMPTS || !plugin.isEnabled()) {
            return;
        }
        runOnMainThreadLater(
                () -> beginRefresh(minecraftUuid, lifecycleEpoch, completedAttempt + 1),
                RETRY_DELAY_TICKS
        );
    }

    private boolean isCurrentLifecycle(UUID minecraftUuid, long lifecycleEpoch) {
        Long current = lifecycleEpochByMinecraftUuid.get(minecraftUuid);
        return current != null && current.longValue() == lifecycleEpoch;
    }

    private boolean isCurrentRefresh(UUID minecraftUuid, long token) {
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
