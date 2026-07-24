package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.control.ZoneRoute;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneRouter;
import io.github.kevinrabbe.minecraftserver.common.session.BackendSessionLeaseRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Owns the Paper-side attachment of authenticated Minecraft players to exclusive persistent session leases. */
final class PaperSessionController implements Listener {
    private static final Duration SESSION_LEASE = Duration.ofSeconds(60);
    private static final Duration TRANSFER_TICKET_LIFETIME = Duration.ofSeconds(30);
    private static final Duration PENDING_LOGIN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ROUTE_HEARTBEAT_FRESHNESS = Duration.ofSeconds(15);

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
    private final PlayerIdentityRepository identities;
    private final PlayerSessionRepository sessions;
    private final BackendSessionLeaseRepository backendLeases;
    private final TransferRecoveryRepository transferRecovery;
    private final TransferRoutingRepository transferRouting;
    private final ZoneRouter zoneRouter;

    private final ConcurrentHashMap<UUID, PendingSession> pendingByMinecraftUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SessionLease> activeByMinecraftUuid = new ConcurrentHashMap<>();

    PaperSessionController(JavaPlugin plugin, String backendId, DataSource dataSource) {
        this.plugin = plugin;
        this.backendId = backendId;
        this.identities = new PlayerIdentityRepository(dataSource);
        this.sessions = new PlayerSessionRepository(dataSource);
        this.backendLeases = new BackendSessionLeaseRepository(dataSource);
        this.transferRecovery = new TransferRecoveryRepository(dataSource);
        this.transferRouting = new TransferRoutingRepository(dataSource, ROUTE_HEARTBEAT_FRESHNESS);
        this.zoneRouter = new ZoneRouter(dataSource, ROUTE_HEARTBEAT_FRESHNESS);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        UUID minecraftUuid = event.getUniqueId();
        try {
            SessionLease lease = acquireLease(minecraftUuid, event.getName());
            PendingSession pending = new PendingSession(lease, Instant.now());
            PendingSession previous = pendingByMinecraftUuid.putIfAbsent(minecraftUuid, pending);
            if (previous != null) {
                backendLeases.disconnectAttachedSession(lease.sessionId(), backendId);
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
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        PendingSession pending = pendingByMinecraftUuid.remove(minecraftUuid);
        if (pending == null) {
            event.getPlayer().kick(SESSION_LOST_MESSAGE);
            return;
        }

        SessionLease previous = activeByMinecraftUuid.putIfAbsent(minecraftUuid, pending.lease());
        if (previous != null) {
            event.getPlayer().kick(SESSION_CONFLICT_MESSAGE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        SessionLease lease = activeByMinecraftUuid.remove(minecraftUuid);
        PendingSession pending = pendingByMinecraftUuid.remove(minecraftUuid);
        if (lease == null && pending != null) {
            lease = pending.lease();
        }
        if (lease == null || !plugin.isEnabled()) {
            return;
        }

        SessionLease finalLease = lease;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> disconnectOrdinarySession(finalLease));
    }

    /** Begins a cross-backend handoff for a logical gameplay zone. Used by the temporary dev command for now. */
    boolean requestZoneTransfer(Player player, String zoneId) {
        String normalizedZoneId = requireZoneId(zoneId);
        SessionLease lease = activeByMinecraftUuid.get(player.getUniqueId());
        if (lease == null) {
            player.sendMessage(Component.text("No active persistent session is attached to this backend."));
            return false;
        }

        try {
            Optional<ZoneRoute> candidate = zoneRouter.findPreferredActiveInstance(normalizedZoneId);
            if (candidate.isEmpty()) {
                player.sendMessage(Component.text("No healthy instance is currently available for zone " + normalizedZoneId + "."));
                return false;
            }
            if (backendId.equals(candidate.orElseThrow().backendId())) {
                player.sendMessage(Component.text(
                        "Zone " + normalizedZoneId + " is hosted on this backend; the cross-backend dev route is not needed."
                ));
                return false;
            }

            TransferTicket ticket = sessions.beginTransfer(
                    lease.sessionId(),
                    backendId,
                    normalizedZoneId,
                    lease.stateVersion(),
                    TRANSFER_TICKET_LIFETIME
            );

            player.sendPluginMessage(plugin, TransferPluginMessage.CHANNEL, TransferPluginMessage.encode(ticket.transferId()));
            player.sendMessage(Component.text("Routing to zone " + normalizedZoneId + "..."));
            return true;
        } catch (SessionConflictException exception) {
            player.sendMessage(SESSION_CONFLICT_MESSAGE);
            return false;
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not begin zone transfer for " + player.getUniqueId(), exception);
            player.sendMessage(STATE_UNAVAILABLE_MESSAGE);
            return false;
        }
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
            transferRecovery.recoverExpiredAttachedTransfers(backendId, attachedSessionIds);
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

    void shutdown() {
        Set<UUID> attached = new HashSet<>();
        for (SessionLease lease : activeByMinecraftUuid.values()) {
            attached.add(lease.sessionId());
        }
        for (PendingSession pending : pendingByMinecraftUuid.values()) {
            attached.add(pending.lease().sessionId());
        }

        if (!attached.isEmpty()) {
            try {
                backendLeases.disconnectAttachedSessions(backendId, attached);
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not release player sessions during shutdown", exception);
            }
        }

        activeByMinecraftUuid.clear();
        pendingByMinecraftUuid.clear();
    }

    Optional<SessionLease> activeSession(UUID minecraftUuid) {
        return Optional.ofNullable(activeByMinecraftUuid.get(minecraftUuid));
    }

    private SessionLease acquireLease(UUID minecraftUuid, String playerName) throws SQLException {
        Optional<RoutedTransfer> transfer = transferRouting.findRoutedTransfer(minecraftUuid);
        if (transfer.isPresent()) {
            RoutedTransfer routed = transfer.orElseThrow();
            if (!backendId.equals(routed.targetBackendId())) {
                throw new SessionConflictException(
                        "Player transfer is routed to another backend: " + routed.targetBackendId()
                );
            }
            return sessions.claimTransfer(
                    routed.transferId(),
                    backendId,
                    routed.targetInstanceId(),
                    SESSION_LEASE
            );
        }

        UUID playerId = identities.ensurePlayer(minecraftUuid, playerName);
        return sessions.openSession(playerId, backendId, null, SESSION_LEASE);
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
            disconnectOrdinarySession(lease);
        }
    }

    private Map<UUID, UUID> activeSessionIndex() {
        Map<UUID, UUID> minecraftUuidBySessionId = new HashMap<>();
        activeByMinecraftUuid.forEach((minecraftUuid, lease) ->
                minecraftUuidBySessionId.put(lease.sessionId(), minecraftUuid));
        return minecraftUuidBySessionId;
    }

    private void handleLostSessions(Set<UUID> lostSessionIds, Map<UUID, UUID> minecraftUuidBySessionId) {
        ArrayList<UUID> playersToKick = new ArrayList<>();
        for (UUID sessionId : lostSessionIds) {
            UUID minecraftUuid = minecraftUuidBySessionId.get(sessionId);
            if (minecraftUuid == null) {
                continue;
            }
            SessionLease lease = activeByMinecraftUuid.get(minecraftUuid);
            if (lease != null
                    && lease.sessionId().equals(sessionId)
                    && activeByMinecraftUuid.remove(minecraftUuid, lease)) {
                playersToKick.add(minecraftUuid);
            }
        }

        if (playersToKick.isEmpty() || !plugin.isEnabled()) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (UUID minecraftUuid : playersToKick) {
                Player player = plugin.getServer().getPlayer(minecraftUuid);
                if (player != null && player.isOnline()) {
                    player.kick(SESSION_LOST_MESSAGE);
                }
            }
        });
    }

    private void disconnectOrdinarySession(SessionLease lease) {
        try {
            backendLeases.disconnectAttachedSession(lease.sessionId(), backendId);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not release player session " + lease.sessionId(), exception);
        }
    }

    private static String requireZoneId(String zoneId) {
        if (zoneId == null || !zoneId.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("zoneId must match [a-z0-9][a-z0-9_-]{0,63}");
        }
        return zoneId;
    }

    private record PendingSession(SessionLease lease, Instant createdAt) {
    }
}
