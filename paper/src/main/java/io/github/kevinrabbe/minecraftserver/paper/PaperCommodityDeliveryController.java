package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.BazaarException;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliveryClaimResult;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliveryRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliverySnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/**
 * Drains durable commodity deliveries into live Paper inventory through the shared serialized-state authority lane.
 * A failed/full/busy delivery remains PENDING and therefore recoverable.
 */
final class PaperCommodityDeliveryController implements Listener {
    private static final String CLAIM_REASON = "delivery.claim";
    private static final long BUSY_RETRY_TICKS = 10L;

    private final JavaPlugin plugin;
    private final DataSource dataSource;
    private final PaperSessionController sessions;
    private final PaperCommodityStateMutator mutator;
    private final CommodityDeliveryRepository deliveries;
    private final Set<UUID> drainInFlight = ConcurrentHashMap.newKeySet();

    PaperCommodityDeliveryController(
            MinecraftServerPlugin plugin,
            DataSource dataSource,
            PaperSessionController sessions,
            PaperCommodityStateMutator mutator
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.mutator = Objects.requireNonNull(mutator, "mutator");
        this.deliveries = new CommodityDeliveryRepository(dataSource, mutator);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        // Run after the session-controller join handler has attached and applied persisted inventory.
        plugin.getServer().getScheduler().runTask(plugin, () -> requestDrain(minecraftUuid));
    }

    /** Safe to call after any system creates a pending commodity delivery. */
    void requestDrain(UUID minecraftUuid) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        if (!plugin.isEnabled() || !drainInFlight.add(minecraftUuid)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> loadNext(minecraftUuid));
        } catch (RejectedExecutionException | IllegalStateException exception) {
            drainInFlight.remove(minecraftUuid);
            plugin.getLogger().log(Level.WARNING, "Could not schedule pending commodity lookup", exception);
        }
    }

    private void loadNext(UUID minecraftUuid) {
        try {
            Optional<UUID> playerId = resolvePlayerId(minecraftUuid);
            if (playerId.isEmpty()) {
                finishDrain(minecraftUuid);
                return;
            }
            var pending = deliveries.listPending(playerId.orElseThrow(), 1);
            if (pending.isEmpty()) {
                finishDrain(minecraftUuid);
                return;
            }
            CommodityDeliverySnapshot delivery = pending.getFirst();
            runOnMainThread(() -> claimOnMainThread(minecraftUuid, delivery));
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not inspect pending commodity deliveries", exception);
            finishDrain(minecraftUuid);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Pending commodity lookup failed closed", exception);
            finishDrain(minecraftUuid);
        }
    }

    private void claimOnMainThread(UUID minecraftUuid, CommodityDeliverySnapshot delivery) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline()) {
            finishDrain(minecraftUuid);
            return;
        }

        if (sessions.isMutationFrozen(minecraftUuid)) {
            retryBusy(minecraftUuid);
            return;
        }

        UUID claimOperationId = UUID.randomUUID();
        sessions.mutateAuthoritativeState(player, context -> {
            byte[] nextPayload = mutator.add(
                    context.playerId(),
                    delivery.commodityDefinitionId(),
                    delivery.quantity(),
                    context.currentStatePayload()
            );
            CommodityDeliveryClaimResult claimed = deliveries.claim(
                    claimOperationId,
                    delivery.deliveryId(),
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    context.logicalZoneId(),
                    context.entryPoint(),
                    nextPayload,
                    CLAIM_REASON
            );
            if (!claimed.playerId().equals(context.playerId())) {
                throw new IllegalStateException("Commodity claim returned another player identity");
            }
            return new PaperAuthoritativeStateMutation.Result(
                    claimed.playerStateVersion(),
                    nextPayload
            );
        }).whenComplete((result, failure) -> {
            if (failure == null) {
                finishDrain(minecraftUuid);
                runOnMainThreadLater(() -> requestDrain(minecraftUuid), 1L);
                return;
            }

            Throwable cause = unwrap(failure);
            finishDrain(minecraftUuid);
            if (cause instanceof BazaarException && cause.getMessage() != null
                    && cause.getMessage().contains("insufficient space")) {
                runOnMainThread(() -> {
                    Player live = plugin.getServer().getPlayer(minecraftUuid);
                    if (live != null && live.isOnline()) {
                        live.sendMessage(Component.text("Your inventory is full. The reward remains safely pending."));
                    }
                });
                return;
            }
            if (cause instanceof io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException) {
                runOnMainThreadLater(() -> requestDrain(minecraftUuid), BUSY_RETRY_TICKS);
                return;
            }
            plugin.getLogger().log(Level.WARNING, "Pending commodity claim failed; delivery remains recoverable", cause);
        });
    }

    private Optional<UUID> resolvePlayerId(UUID minecraftUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id
                     FROM players
                     WHERE minecraft_uuid = ?
                     """)) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                UUID playerId = row.getObject("player_id", UUID.class);
                if (row.next()) {
                    throw new IllegalStateException("Minecraft UUID resolved to multiple stable player identities");
                }
                return Optional.of(playerId);
            }
        }
    }

    private void retryBusy(UUID minecraftUuid) {
        finishDrain(minecraftUuid);
        runOnMainThreadLater(() -> requestDrain(minecraftUuid), BUSY_RETRY_TICKS);
    }

    private void finishDrain(UUID minecraftUuid) {
        drainInFlight.remove(minecraftUuid);
    }

    private void runOnMainThread(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private void runOnMainThreadLater(Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
