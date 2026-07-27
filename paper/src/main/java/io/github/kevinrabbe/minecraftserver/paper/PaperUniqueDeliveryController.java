package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationValidationResult;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRuntimeStatSnapshot;
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
import java.util.List;
import java.util.Map;
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
    private static final long STAT_REFRESH_RETRY_TICKS = 20L;
    private static final Component INVALID_ITEM_STATE_MESSAGE = Component.text(
            "Your carried item state failed authority validation and has been isolated. Please contact staff."
    );

    private final JavaPlugin plugin;
    private final DataSource dataSource;
    private final PaperSessionController sessions;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final PendingUniqueDeliveryClaimService claims;
    private final PaperPlayerItemRepresentationValidator statValidator;
    private final PaperItemRuntimePresentation presentation;
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
        this.statValidator = new PaperPlayerItemRepresentationValidator(plugin, dataSource, catalog);
        this.presentation = new PaperItemRuntimePresentation(plugin, catalog);
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
                runOnMainThreadLater(() -> {
                    requestStatRefresh(minecraftUuid);
                    requestDrain(minecraftUuid);
                }, 1L);
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

    /** Captures Bukkit inventory on the main thread, then performs the authority query asynchronously. */
    private void requestStatRefresh(UUID minecraftUuid) {
        if (!plugin.isEnabled()) return;
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline()) return;

        final List<ItemRepresentationClaim> capturedClaims;
        try {
            capturedClaims = statValidator.collectClaims(player);
        } catch (RuntimeException exception) {
            PaperItemRuntimeStatCache.clear(minecraftUuid);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not read delivered item identity metadata for player " + minecraftUuid,
                    exception
            );
            player.kick(INVALID_ITEM_STATE_MESSAGE);
            return;
        }

        long generation = PaperItemRuntimeStatCache.beginRefresh(minecraftUuid);
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> refreshStatsOffThread(minecraftUuid, generation, capturedClaims)
            );
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule delivered item stat validation", exception);
            runOnMainThreadLater(() -> requestStatRefresh(minecraftUuid), STAT_REFRESH_RETRY_TICKS);
        }
    }

    private void refreshStatsOffThread(
            UUID minecraftUuid,
            long generation,
            List<ItemRepresentationClaim> capturedClaims
    ) {
        try {
            ItemRepresentationValidationResult validation = statValidator.validateAndSnapshot(
                    minecraftUuid,
                    capturedClaims
            );
            if (validation.valid()) {
                Map<UUID, ItemRuntimeStatSnapshot> snapshots = validation.validatedIndividualSnapshots();
                if (PaperItemRuntimeStatCache.replaceIfCurrent(minecraftUuid, generation, snapshots)) {
                    runOnMainThread(() -> refreshPresentationBestEffort(minecraftUuid));
                }
                return;
            }

            if (PaperItemRuntimeStatCache.invalidateIfCurrent(minecraftUuid, generation)) {
                plugin.getLogger().severe(
                        "Delivered item state failed authority validation for player " + minecraftUuid
                                + "; issues=" + validation.issues()
                );
                runOnMainThread(() -> {
                    Player live = plugin.getServer().getPlayer(minecraftUuid);
                    if (live != null && live.isOnline()) live.kick(INVALID_ITEM_STATE_MESSAGE);
                });
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not refresh delivered item runtime stats for player " + minecraftUuid,
                    exception
            );
            runOnMainThreadLater(() -> requestStatRefresh(minecraftUuid), STAT_REFRESH_RETRY_TICKS);
        } catch (RuntimeException exception) {
            if (PaperItemRuntimeStatCache.invalidateIfCurrent(minecraftUuid, generation)) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Delivered item runtime-stat validation failed closed for player " + minecraftUuid,
                        exception
                );
                runOnMainThread(() -> {
                    Player live = plugin.getServer().getPlayer(minecraftUuid);
                    if (live != null && live.isOnline()) live.kick(INVALID_ITEM_STATE_MESSAGE);
                });
            }
        }
    }

    private void refreshPresentationBestEffort(UUID minecraftUuid) {
        Player live = plugin.getServer().getPlayer(minecraftUuid);
        if (live == null || !live.isOnline()) return;
        try {
            presentation.refresh(live);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not refresh derived managed-item presentation after delivery for player " + minecraftUuid,
                    exception
            );
        }
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
