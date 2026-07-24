package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.control.ZoneRoute;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneRouter;
import io.github.kevinrabbe.minecraftserver.common.session.BackendSessionLeaseRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateSnapshot;
import io.github.kevinrabbe.minecraftserver.common.session.RoutedTransfer;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import io.github.kevinrabbe.minecraftserver.common.session.TransferRecoveryRepository;
import io.github.kevinrabbe.minecraftserver.common.session.TransferRoutingRepository;
import io.github.kevinrabbe.minecraftserver.common.session.TransferTicket;
import io.github.kevinrabbe.minecraftserver.common.transfer.TransferPluginMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/** Owns Paper-side attachment, checkpointing, and transfer of network-authoritative player state. */
final class PaperSessionController implements Listener {
    private static final Duration SESSION_LEASE = Duration.ofSeconds(60);
    private static final Duration TRANSFER_TICKET_LIFETIME = Duration.ofSeconds(30);
    private static final Duration PENDING_LOGIN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ROUTE_HEARTBEAT_FRESHNESS = Duration.ofSeconds(15);
    private static final int PERSISTENCE_WORKERS = 2;
    private static final int PERSISTENCE_QUEUE_CAPACITY = 256;
    private static final long SHUTDOWN_COMMIT_TIMEOUT_SECONDS = 10;

    private static final Component SESSION_CONFLICT_MESSAGE = Component.text(
            "Your persistent player session is already active or transferring. Please retry shortly."
    );
    private static final Component STATE_UNAVAILABLE_MESSAGE = Component.text(
            "Persistent player state is temporarily unavailable. Please retry shortly."
    );
    private static final Component SESSION_LOST_MESSAGE = Component.text(
            "Your persistent player session lease was lost. Please reconnect."
    );

    private final JavaPlugin plugin;
    private final String backendId;
    private final String currentZoneId;
    private final PlayerIdentityRepository identities;
    private final PlayerSessionRepository sessions;
    private final PlayerStateRepository states;
    private final BackendSessionLeaseRepository backendLeases;
    private final TransferRecoveryRepository transferRecovery;
    private final TransferRoutingRepository transferRouting;
    private final ZoneRouter zoneRouter;
    private final PaperPlayerStateCodec stateCodec = new PaperPlayerStateCodec();
    private final ThreadPoolExecutor persistenceExecutor;

    private final ConcurrentHashMap<UUID, PendingSession> pendingByMinecraftUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AttachedPlayerSession> activeByMinecraftUuid = new ConcurrentHashMap<>();

    PaperSessionController(JavaPlugin plugin, String backendId, String currentZoneId, DataSource dataSource) {
        this.plugin = plugin;
        this.backendId = backendId;
        this.currentZoneId = normalizeOptional(currentZoneId);
        this.identities = new PlayerIdentityRepository(dataSource);
        this.sessions = new PlayerSessionRepository(dataSource);
        this.states = new PlayerStateRepository(dataSource);
        this.backendLeases = new BackendSessionLeaseRepository(dataSource);
        this.transferRecovery = new TransferRecoveryRepository(dataSource);
        this.transferRouting = new TransferRoutingRepository(dataSource, ROUTE_HEARTBEAT_FRESHNESS);
        this.zoneRouter = new ZoneRouter(dataSource, ROUTE_HEARTBEAT_FRESHNESS);
        this.persistenceExecutor = new ThreadPoolExecutor(
                PERSISTENCE_WORKERS,
                PERSISTENCE_WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PERSISTENCE_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "minecraft-player-state");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        UUID minecraftUuid = event.getUniqueId();
        try {
            AcquiredState acquired = acquireState(minecraftUuid, event.getName());
            PendingSession pending = new PendingSession(acquired.lease(), acquired.snapshot(), Instant.now());
            PendingSession previous = pendingByMinecraftUuid.putIfAbsent(minecraftUuid, pending);
            if (previous != null) {
                releaseOrdinaryLease(acquired.lease());
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, SESSION_CONFLICT_MESSAGE);
            }
        } catch (SessionConflictException exception) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, SESSION_CONFLICT_MESSAGE);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not establish persistent player session", exception);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, STATE_UNAVAILABLE_MESSAGE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID minecraftUuid = player.getUniqueId();
        PendingSession pending = pendingByMinecraftUuid.remove(minecraftUuid);
        if (pending == null) {
            player.kick(SESSION_LOST_MESSAGE);
            return;
        }

        try {
            stateCodec.apply(player, pending.snapshot().statePayload());
            AttachedPlayerSession attached = new AttachedPlayerSession(
                    pending.lease(),
                    pending.snapshot(),
                    states,
                    persistenceExecutor
            );
            AttachedPlayerSession previous = activeByMinecraftUuid.putIfAbsent(minecraftUuid, attached);
            if (previous != null) {
                attached.closeAttachment();
                releaseLeaseAsync(pending.lease());
                player.kick(SESSION_CONFLICT_MESSAGE);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not apply authoritative player inventory state", exception);
            releaseLeaseAsync(pending.lease());
            player.kick(STATE_UNAVAILABLE_MESSAGE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID minecraftUuid = player.getUniqueId();
        AttachedPlayerSession attached = activeByMinecraftUuid.remove(minecraftUuid);
        PendingSession pending = pendingByMinecraftUuid.remove(minecraftUuid);

        if (attached == null) {
            if (pending != null && plugin.isEnabled()) {
                releaseLeaseAsync(pending.lease());
            }
            return;
        }

        attached.closeAttachment();
        if (attached.isTransferStarted()) {
            return;
        }

        byte[] payload;
        try {
            payload = stateCodec.capture(player);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not capture final player inventory on logout", exception);
            return;
        }

        try {
            attached.checkpoint(payload, currentZoneId, null, true)
                    .whenComplete((version, failure) -> {
                        if (failure != null) {
                            logCheckpointFailure("logout", minecraftUuid, failure);
                            return;
                        }
                        disconnectOrdinarySession(attached.sessionId());
                    });
        } catch (RejectedExecutionException exception) {
            plugin.getLogger().log(Level.SEVERE, "Persistence queue rejected final logout checkpoint", exception);
        }
    }

    /** Main-thread periodic checkpoint. Captures Bukkit state only on the server thread, then writes asynchronously. */
    void checkpointOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            AttachedPlayerSession attached = activeByMinecraftUuid.get(player.getUniqueId());
            if (attached == null || attached.isClosed() || attached.isFrozen() || attached.hasCheckpointInFlight()) {
                continue;
            }

            try {
                byte[] payload = stateCodec.capture(player);
                attached.checkpoint(payload, currentZoneId, null, false)
                        .whenComplete((version, failure) -> {
                            if (failure != null) {
                                logCheckpointFailure("periodic", player.getUniqueId(), failure);
                            }
                        });
            } catch (RejectedExecutionException exception) {
                plugin.getLogger().log(Level.WARNING, "Persistence queue is full; checkpoint deferred", exception);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not capture player inventory for checkpoint", exception);
            }
        }
    }

    /** Begins a cross-backend handoff for a logical gameplay zone. Used by the temporary dev command for now. */
    boolean requestZoneTransfer(Player player, String zoneId) {
        String normalizedZoneId = requireZoneId(zoneId);
        AttachedPlayerSession attached = activeByMinecraftUuid.get(player.getUniqueId());
        if (attached == null || !attached.freezeForTransfer()) {
            player.sendMessage(Component.text("No mutable persistent session is available for transfer."));
            return false;
        }

        final byte[] finalPayload;
        try {
            finalPayload = stateCodec.capture(player);
        } catch (RuntimeException exception) {
            attached.transferFailedOrExpired();
            plugin.getLogger().log(Level.WARNING, "Could not capture final transfer checkpoint", exception);
            player.sendMessage(STATE_UNAVAILABLE_MESSAGE);
            return false;
        }

        CompletableFuture<TransferTicket> transferFuture;
        try {
            transferFuture = attached.checkpoint(finalPayload, currentZoneId, null, true)
                    .thenApplyAsync(committedVersion -> prepareTransfer(
                            attached,
                            normalizedZoneId,
                            committedVersion
                    ), persistenceExecutor);
        } catch (RejectedExecutionException exception) {
            attached.transferFailedOrExpired();
            plugin.getLogger().log(Level.WARNING, "Persistence queue rejected transfer checkpoint", exception);
            player.sendMessage(STATE_UNAVAILABLE_MESSAGE);
            return false;
        }

        transferFuture.whenComplete((ticket, failure) -> runOnMainThread(() -> {
            if (failure != null) {
                if (!attached.isTransferStarted()) {
                    attached.transferFailedOrExpired();
                }
                if (player.isOnline()) {
                    player.sendMessage(Component.text(transferFailureMessage(failure)));
                }
                return;
            }

            if (!player.isOnline()) {
                return;
            }

            try {
                player.sendPluginMessage(
                        plugin,
                        TransferPluginMessage.CHANNEL,
                        TransferPluginMessage.encode(ticket.transferId())
                );
                player.sendMessage(Component.text("Routing to zone " + normalizedZoneId + "..."));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not signal Velocity after transfer ticket creation", exception);
                player.sendMessage(Component.text("Transfer signal failed; the session will recover automatically."));
            }
        }));
        return true;
    }

    /** Called from the plugin's asynchronous heartbeat task. */
    void heartbeat() {
        expirePendingLogins();

        Map<UUID, UUID> minecraftUuidBySessionId = activeSessionIndex();
        if (minecraftUuidBySessionId.isEmpty()) {
            return;
        }

        Set<UUID> attachedSessionIds = Set.copyOf(minecraftUuidBySessionId.keySet());
        try {
            Set<UUID> recovered = transferRecovery.recoverExpiredAttachedTransfers(backendId, attachedSessionIds);
            if (!recovered.isEmpty()) {
                recoverLocalTransfers(recovered, minecraftUuidBySessionId);
            }

            Set<UUID> renewed = backendLeases.heartbeatAttachedSessions(
                    backendId,
                    attachedSessionIds,
                    SESSION_LEASE
            );

            Set<UUID> lost = new HashSet<>(attachedSessionIds);
            lost.removeAll(renewed);
            if (!lost.isEmpty()) {
                handleLostSessions(lost, minecraftUuidBySessionId);
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Player session heartbeat failed", exception);
        }
    }

    /** Main-thread controlled shutdown: capture state, wait a bounded time for commits, then release ordinary leases. */
    void shutdown() {
        ArrayList<CompletableFuture<Long>> finalCommits = new ArrayList<>();
        ArrayList<AttachedPlayerSession> ordinarySessions = new ArrayList<>();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            AttachedPlayerSession attached = activeByMinecraftUuid.remove(player.getUniqueId());
            if (attached == null) {
                continue;
            }
            attached.closeAttachment();
            if (attached.isTransferStarted()) {
                continue;
            }

            ordinarySessions.add(attached);
            try {
                finalCommits.add(attached.checkpoint(
                        stateCodec.capture(player),
                        currentZoneId,
                        null,
                        true
                ));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not queue final shutdown checkpoint", exception);
            }
        }

        for (PendingSession pending : pendingByMinecraftUuid.values()) {
            releaseOrdinaryLease(pending.lease());
        }
        pendingByMinecraftUuid.clear();

        if (!finalCommits.isEmpty()) {
            try {
                CompletableFuture.allOf(finalCommits.toArray(CompletableFuture[]::new))
                        .get(SHUTDOWN_COMMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                plugin.getLogger().log(Level.WARNING, "Interrupted while waiting for final player-state commits", exception);
            } catch (ExecutionException | TimeoutException exception) {
                plugin.getLogger().log(Level.SEVERE, "Final player-state shutdown checkpoint did not fully succeed", exception);
            }
        }

        for (AttachedPlayerSession attached : ordinarySessions) {
            disconnectOrdinarySession(attached.sessionId());
        }

        activeByMinecraftUuid.clear();
        persistenceExecutor.shutdown();
        try {
            if (!persistenceExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                persistenceExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            persistenceExecutor.shutdownNow();
        }
    }

    boolean isMutationFrozen(UUID minecraftUuid) {
        AttachedPlayerSession attached = activeByMinecraftUuid.get(minecraftUuid);
        return attached != null && attached.isFrozen();
    }

    private AcquiredState acquireState(UUID minecraftUuid, String playerName) throws SQLException {
        SessionLease lease;
        Optional<RoutedTransfer> transfer = transferRouting.findRoutedTransfer(minecraftUuid);
        if (transfer.isPresent()) {
            RoutedTransfer routed = transfer.orElseThrow();
            if (!backendId.equals(routed.targetBackendId())) {
                throw new SessionConflictException(
                        "Player transfer is routed to another backend: " + routed.targetBackendId()
                );
            }
            lease = sessions.claimTransfer(
                    routed.transferId(),
                    backendId,
                    routed.targetInstanceId(),
                    SESSION_LEASE
            );
        } else {
            UUID playerId = identities.ensurePlayer(minecraftUuid, playerName);
            lease = sessions.openSession(playerId, backendId, null, SESSION_LEASE);
        }

        try {
            PlayerStateSnapshot snapshot = states.load(lease.playerId());
            if (snapshot.stateVersion() != lease.stateVersion()) {
                throw new SessionConflictException("Loaded player state does not match acquired session version");
            }
            return new AcquiredState(lease, snapshot);
        } catch (SQLException | RuntimeException exception) {
            try {
                backendLeases.disconnectAttachedSession(lease.sessionId(), backendId);
            } catch (SQLException releaseFailure) {
                exception.addSuppressed(releaseFailure);
            }
            throw exception;
        }
    }

    private TransferTicket prepareTransfer(
            AttachedPlayerSession attached,
            String targetZoneId,
            long committedVersion
    ) {
        if (attached.isClosed()) {
            throw new TransferPreparationException("Player left before the transfer could begin");
        }

        try {
            Optional<ZoneRoute> candidate = zoneRouter.findPreferredActiveInstance(targetZoneId);
            if (candidate.isEmpty()) {
                throw new TransferPreparationException("No healthy instance is currently available for zone " + targetZoneId + ".");
            }
            if (backendId.equals(candidate.orElseThrow().backendId())) {
                throw new TransferPreparationException(
                        "Zone " + targetZoneId + " is hosted on this backend; cross-backend routing is not needed."
                );
            }
            if (attached.isClosed()) {
                throw new TransferPreparationException("Player left before the transfer could begin");
            }

            TransferTicket ticket = sessions.beginTransfer(
                    attached.sessionId(),
                    backendId,
                    targetZoneId,
                    committedVersion,
                    TRANSFER_TICKET_LIFETIME
            );
            attached.transferStarted();
            return ticket;
        } catch (SQLException exception) {
            throw new TransferPreparationException("Persistent routing is temporarily unavailable.", exception);
        }
    }

    private void expirePendingLogins() {
        Instant cutoff = Instant.now().minus(PENDING_LOGIN_TIMEOUT);
        ArrayList<SessionLease> expired = new ArrayList<>();

        pendingByMinecraftUuid.forEach((minecraftUuid, pending) -> {
            if (pending.createdAt().isBefore(cutoff)
                    && pendingByMinecraftUuid.remove(minecraftUuid, pending)) {
                expired.add(pending.lease());
            }
        });

        for (SessionLease lease : expired) {
            releaseOrdinaryLease(lease);
        }
    }

    private Map<UUID, UUID> activeSessionIndex() {
        Map<UUID, UUID> minecraftUuidBySessionId = new HashMap<>();
        activeByMinecraftUuid.forEach((minecraftUuid, attached) -> {
            if (!attached.isClosed()) {
                minecraftUuidBySessionId.put(attached.sessionId(), minecraftUuid);
            }
        });
        return minecraftUuidBySessionId;
    }

    private void recoverLocalTransfers(Set<UUID> recoveredSessionIds, Map<UUID, UUID> minecraftUuidBySessionId) {
        ArrayList<UUID> recoveredPlayers = new ArrayList<>();
        for (UUID sessionId : recoveredSessionIds) {
            UUID minecraftUuid = minecraftUuidBySessionId.get(sessionId);
            if (minecraftUuid == null) {
                continue;
            }
            AttachedPlayerSession attached = activeByMinecraftUuid.get(minecraftUuid);
            if (attached != null && attached.sessionId().equals(sessionId)) {
                attached.transferFailedOrExpired();
                recoveredPlayers.add(minecraftUuid);
            }
        }

        if (!recoveredPlayers.isEmpty()) {
            runOnMainThread(() -> {
                for (UUID minecraftUuid : recoveredPlayers) {
                    Player player = plugin.getServer().getPlayer(minecraftUuid);
                    if (player != null && player.isOnline()) {
                        player.sendMessage(Component.text("Transfer timed out; your source session is active again."));
                    }
                }
            });
        }
    }

    private void handleLostSessions(Set<UUID> lostSessionIds, Map<UUID, UUID> minecraftUuidBySessionId) {
        ArrayList<UUID> playersToKick = new ArrayList<>();
        for (UUID sessionId : lostSessionIds) {
            UUID minecraftUuid = minecraftUuidBySessionId.get(sessionId);
            if (minecraftUuid == null) {
                continue;
            }
            AttachedPlayerSession attached = activeByMinecraftUuid.get(minecraftUuid);
            if (attached != null
                    && attached.sessionId().equals(sessionId)
                    && activeByMinecraftUuid.remove(minecraftUuid, attached)) {
                attached.closeAttachment();
                playersToKick.add(minecraftUuid);
            }
        }

        if (!playersToKick.isEmpty()) {
            runOnMainThread(() -> {
                for (UUID minecraftUuid : playersToKick) {
                    Player player = plugin.getServer().getPlayer(minecraftUuid);
                    if (player != null && player.isOnline()) {
                        player.kick(SESSION_LOST_MESSAGE);
                    }
                }
            });
        }
    }

    private void releaseLeaseAsync(SessionLease lease) {
        try {
            persistenceExecutor.execute(() -> releaseOrdinaryLease(lease));
        } catch (RejectedExecutionException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule session release", exception);
        }
    }

    private void releaseOrdinaryLease(SessionLease lease) {
        disconnectOrdinarySession(lease.sessionId());
    }

    private void disconnectOrdinarySession(UUID sessionId) {
        try {
            backendLeases.disconnectAttachedSession(sessionId, backendId);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not release player session " + sessionId, exception);
        }
    }

    private void runOnMainThread(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private void logCheckpointFailure(String type, UUID minecraftUuid, Throwable failure) {
        Throwable cause = unwrapCompletionFailure(failure);
        plugin.getLogger().log(Level.WARNING, "Player-state " + type + " checkpoint failed for " + minecraftUuid, cause);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String transferFailureMessage(Throwable failure) {
        Throwable cause = unwrapCompletionFailure(failure);
        if (cause instanceof TransferPreparationException && cause.getMessage() != null) {
            return cause.getMessage();
        }
        return "Transfer preparation failed; your source session remains active.";
    }

    private static String requireZoneId(String zoneId) {
        if (zoneId == null || !zoneId.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("zoneId must match [a-z0-9][a-z0-9_-]{0,63}");
        }
        return zoneId;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record PendingSession(SessionLease lease, PlayerStateSnapshot snapshot, Instant createdAt) {
    }

    private record AcquiredState(SessionLease lease, PlayerStateSnapshot snapshot) {
    }

    private static final class TransferPreparationException extends RuntimeException {
        private TransferPreparationException(String message) {
            super(message);
        }

        private TransferPreparationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
