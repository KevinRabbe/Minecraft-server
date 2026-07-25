package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryClaimService;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryException;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryMaterializationResult;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
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

/** Generic pending unique-item drain for crafting, AH, trade, clan, Map, and future authoritative issuers. */
final class PaperUniqueDeliveryController implements Listener {
    private static final String CLAIM_REASON = "delivery.unique_claim";
    private static final long BUSY_RETRY_TICKS = 10L;

    private final JavaPlugin plugin;
    private final DataSource dataSource;
    private final PaperSessionController sessions;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final PendingUniqueDeliveryClaimService claims;
    private final Set<UUID> drainInFlight = ConcurrentHashMap.newKeySet();

    PaperUniqueDeliveryController(
            MinecraftServerPlugin plugin,
            DataSource dataSource,
            PaperSessionController sessions,
            PaperPlayerIdentityResolver playerIdentities,
            ItemCatalog itemCatalog
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        ItemCatalog catalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.claims = new PendingUniqueDeliveryClaimService(
                new PendingUniqueDeliveryRepository(dataSource, catalog),
                new UniqueItemAuthorityRepository(dataSource, catalog),
                new PaperUniqueDeliveryStateMutator(plugin)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> requestDrain(minecraftUuid));
    }

    /** Safe to call after any authority creates a pending unique-item delivery. */
    void requestDrain(UUID minecraftUuid) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        if (!plugin.isEnabled() || !drainInFlight.add(minecraftUuid)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> loadNext(minecraftUuid));
        } catch (RejectedExecutionException | IllegalStateException exception) {
            drainInFlight.remove(minecraftUuid);
            plugin.getLogger().log(Level.WARNING, "Could not schedule pending unique-item lookup", exception);
        }
    }

    private void loadNext(UUID minecraftUuid) {
        try {
            Optional<UUID> playerId = playerIdentities.resolve(minecraftUuid);
            if (playerId.isEmpty()) {
                finishDrain(minecraftUuid);
                return;
            }
            Optional<UUID> deliveryId = findNextPendingDelivery(playerId.orElseThrow());
            if (deliveryId.isEmpty()) {
                finishDrain(minecraftUuid);
                return;
            }
            runOnMainThread(() -> claimOnMainThread(minecraftUuid, deliveryId.orElseThrow()));
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not inspect pending unique-item deliveries", exception);
            finishDrain(minecraftUuid);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Pending unique-item lookup failed closed", exception);
            finishDrain(minecraftUuid);
        }
    }

    private void claimOnMainThread(UUID minecraftUuid, UUID deliveryId) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline()) {
            finishDrain(minecraftUuid);
            return;
        }
        if (sessions.isMutationFrozen(minecraftUuid)) {
            retryBusy(minecraftUuid);
            return;
        }

        UUID operationId = claimOperationId(deliveryId);
        sessions.mutateAuthoritativeState(player, context -> {
            PendingUniqueDeliveryMaterializationResult materialized = claims.claim(
                    operationId,
                    deliveryId,
                    context.sessionId(),
                    context.backendId(),
                    context.stateVersion(),
                    context.logicalZoneId(),
                    context.entryPoint(),
                    context.currentStatePayload(),
                    CLAIM_REASON
            );
            return new PaperAuthoritativeStateMutation.Result(
                    materialized.claim().playerStateVersion(),
                    materialized.statePayload()
            );
        }).whenComplete((result, failure) -> {
            finishDrain(minecraftUuid);
            if (failure == null) {
                runOnMainThreadLater(() -> requestDrain(minecraftUuid), 1L);
                return;
            }

            Throwable cause = unwrap(failure);
            if (cause instanceof SessionConflictException) {
                runOnMainThreadLater(() -> requestDrain(minecraftUuid), BUSY_RETRY_TICKS);
                return;
            }
            if (cause instanceof PendingUniqueDeliveryException deliveryFailure) {
                String message = deliveryFailure.getMessage();
                if (message != null && message.contains("insufficient space")) {
                    runOnMainThread(() -> {
                        Player live = plugin.getServer().getPlayer(minecraftUuid);
                        if (live != null && live.isOnline()) {
                            live.sendMessage(Component.text(
                                    "Your inventory is full. The unique item remains safely pending."
                            ));
                        }
                    });
                    return;
                }
                if (message != null && message.contains("already claimed")) {
                    runOnMainThreadLater(() -> requestDrain(minecraftUuid), 1L);
                    return;
                }
            }
            plugin.getLogger().log(
                    Level.WARNING,
                    "Pending unique-item claim failed; delivery remains recoverable",
                    cause
            );
        });
    }

    private Optional<UUID> findNextPendingDelivery(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT delivery_id
                     FROM pending_unique_deliveries
                     WHERE recipient_player_id = ?
                       AND status = 'PENDING'
                     ORDER BY created_at, delivery_id
                     LIMIT 1
                     """)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getObject("delivery_id", UUID.class))
                        : Optional.empty();
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
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private void runOnMainThreadLater(Runnable task, long delayTicks) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    private static UUID claimOperationId(UUID deliveryId) {
        return UUID.nameUUIDFromBytes(
                ("paper-unique-delivery-claim:" + deliveryId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
