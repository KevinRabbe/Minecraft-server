package io.github.kevinrabbe.minecraftserver.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneRouter;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveEntryRoute;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveEntryRouteRepository;
import io.github.kevinrabbe.minecraftserver.common.session.RoutedTransfer;
import io.github.kevinrabbe.minecraftserver.common.session.TransferRoutingRepository;
import io.github.kevinrabbe.minecraftserver.common.transfer.TransferPluginMessage;
import net.kyori.adventure.text.Component;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Plugin(
        id = "minecraft-network",
        name = "Minecraft Network",
        version = "0.1.0-SNAPSHOT",
        description = "Velocity routing and network coordination"
)
public final class MinecraftNetworkPlugin {
    private static final System.Logger LOGGER = System.getLogger(MinecraftNetworkPlugin.class.getName());
    private static final Duration INSTANCE_HEARTBEAT_FRESHNESS = Duration.ofSeconds(15);
    private static final Duration COMPETITIVE_BACKEND_HEARTBEAT_FRESHNESS = Duration.ofSeconds(30);
    private static final MinecraftChannelIdentifier TRANSFER_CHANNEL = MinecraftChannelIdentifier.from(
            TransferPluginMessage.CHANNEL
    );

    private final ProxyServer proxy;

    private Database database;
    private ZoneRouter zoneRouter;
    private TransferRoutingRepository transferRouting;
    private CompetitiveEntryRouteRepository competitiveEntryRoutes;

    @Inject
    public MinecraftNetworkPlugin(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            database = Database.open(DatabaseConfig.fromEnvironment());
            database.migrate();
            zoneRouter = new ZoneRouter(database.dataSource(), INSTANCE_HEARTBEAT_FRESHNESS);
            transferRouting = new TransferRoutingRepository(database.dataSource(), INSTANCE_HEARTBEAT_FRESHNESS);
            competitiveEntryRoutes = new CompetitiveEntryRouteRepository(
                    database.dataSource(),
                    COMPETITIVE_BACKEND_HEARTBEAT_FRESHNESS
            );
            proxy.getChannelRegistrar().register(TRANSFER_CHANNEL);
        } catch (RuntimeException exception) {
            closeDatabase();
            throw exception;
        }

        LOGGER.log(System.Logger.Level.INFO, "Minecraft network plugin initialized with routed transfer coordination");
    }

    /**
     * Keeps any player with one exact ACTIVE competitive execution pinned to its assigned legacy backend.
     * The proxy consumes only the routing projection; it never receives MMO inventory, economy, or custody state.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }

        Player player = event.getPlayer();
        try {
            Optional<CompetitiveEntryRoute> routeResult = competitiveEntryRoutes.findByMinecraftUuid(player.getUniqueId());
            if (routeResult.isEmpty()) {
                return;
            }

            CompetitiveEntryRoute route = routeResult.orElseThrow();
            Optional<RegisteredServer> targetResult = proxy.getServer(route.backendId());
            if (targetResult.isEmpty()) {
                LOGGER.log(
                        System.Logger.Level.ERROR,
                        "Competitive backend is not registered in Velocity: " + route.backendId()
                );
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                player.sendMessage(Component.text("Your competitive match backend is temporarily unavailable."));
                return;
            }

            event.setResult(ServerPreConnectEvent.ServerResult.allowed(targetResult.orElseThrow()));
        } catch (SQLException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Could not resolve competitive player route", exception);
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(Component.text("Competitive routing is temporarily unavailable."));
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!TRANSFER_CHANNEL.equals(event.getIdentifier())) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection sourceConnection)) {
            return;
        }

        UUID requestedTransferId;
        try {
            requestedTransferId = TransferPluginMessage.decode(event.getData());
        } catch (IllegalArgumentException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Rejected malformed transfer plugin message", exception);
            return;
        }

        Player player = sourceConnection.getPlayer();
        try {
            Optional<RoutedTransfer> routedResult = transferRouting.routeForPlayer(player.getUniqueId());
            if (routedResult.isEmpty()) {
                player.sendMessage(Component.text("No healthy instance is currently available for that zone."));
                return;
            }

            RoutedTransfer routed = routedResult.orElseThrow();
            if (!requestedTransferId.equals(routed.transferId())) {
                LOGGER.log(System.Logger.Level.WARNING, "Transfer message id did not match the player's open ticket");
                return;
            }

            String sourceBackendId = sourceConnection.getServerInfo().getName();
            if (!sourceBackendId.equals(routed.sourceBackendId())) {
                LOGGER.log(System.Logger.Level.WARNING, "Transfer message arrived from a backend that does not own the ticket");
                return;
            }

            if (sourceBackendId.equals(routed.targetBackendId())) {
                player.sendMessage(Component.text("This zone is already hosted by the current backend."));
                return;
            }

            Optional<RegisteredServer> targetResult = proxy.getServer(routed.targetBackendId());
            if (targetResult.isEmpty()) {
                LOGGER.log(
                        System.Logger.Level.ERROR,
                        "Routed backend is not registered in Velocity: " + routed.targetBackendId()
                );
                player.sendMessage(Component.text("The routed backend is not available at the proxy."));
                return;
            }

            RegisteredServer target = targetResult.orElseThrow();
            player.createConnectionRequest(target).connectWithIndication().whenComplete((success, failure) -> {
                if (failure != null) {
                    LOGGER.log(System.Logger.Level.WARNING, "Cross-backend transfer connection failed", failure);
                } else if (!Boolean.TRUE.equals(success)) {
                    LOGGER.log(System.Logger.Level.WARNING, "Cross-backend transfer was not completed");
                }
            });
        } catch (SQLException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "Could not route persistent player transfer", exception);
            player.sendMessage(Component.text("Persistent routing is temporarily unavailable."));
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        proxy.getChannelRegistrar().unregister(TRANSFER_CHANNEL);
        competitiveEntryRoutes = null;
        transferRouting = null;
        zoneRouter = null;
        closeDatabase();
    }

    ZoneRouter zoneRouter() {
        if (zoneRouter == null) {
            throw new IllegalStateException("Zone router is not initialized");
        }
        return zoneRouter;
    }

    private void closeDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
