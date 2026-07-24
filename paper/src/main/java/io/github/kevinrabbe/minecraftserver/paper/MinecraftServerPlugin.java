package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.transfer.TransferPluginMessage;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class MinecraftServerPlugin extends JavaPlugin implements Listener {
    private static final long HEARTBEAT_PERIOD_TICKS = 100L;

    private final AtomicInteger onlinePlayers = new AtomicInteger();

    private String backendId;
    private Database database;
    private BackendRegistry backendRegistry;
    private PaperSessionController sessionController;
    private BootstrapZoneInstance bootstrapZoneInstance;
    private BukkitTask heartbeatTask;

    @Override
    public void onEnable() {
        backendId = requireBackendId();

        try {
            database = Database.open(DatabaseConfig.fromEnvironment());
            database.migrate();

            backendRegistry = new BackendRegistry(database.dataSource());
            onlinePlayers.set(getServer().getOnlinePlayers().size());
            backendRegistry.registerOnline(backendId, onlinePlayers.get());

            Optional<BootstrapZoneInstance> configuredZone = BootstrapZoneInstance.fromEnvironment(
                    backendId,
                    database.dataSource()
            );
            if (configuredZone.isPresent()) {
                bootstrapZoneInstance = configuredZone.orElseThrow();
                bootstrapZoneInstance.start();
            }

            sessionController = new PaperSessionController(this, backendId, database.dataSource());
        } catch (RuntimeException | SQLException exception) {
            markBackendOfflineQuietly();
            closeDatabase();
            throw new IllegalStateException("Failed to initialize persistent network foundation", exception);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(sessionController, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, TransferPluginMessage.CHANNEL);

        PluginCommand devZone = Objects.requireNonNull(getCommand("devzone"), "devzone command missing from plugin.yml");
        devZone.setExecutor(new DevZoneCommand(sessionController));

        heartbeatTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                this::sendHeartbeat,
                HEARTBEAT_PERIOD_TICKS,
                HEARTBEAT_PERIOD_TICKS
        );

        String zoneDescription = bootstrapZoneInstance == null
                ? "no bootstrap zone"
                : "bootstrap zone " + bootstrapZoneInstance.zoneId();
        getLogger().info(() -> "Started backend " + backendId + " with " + zoneDescription);
    }

    @Override
    public void onDisable() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }

        if (sessionController != null) {
            sessionController.shutdown();
            sessionController = null;
        }

        if (bootstrapZoneInstance != null) {
            try {
                bootstrapZoneInstance.stop();
            } catch (SQLException exception) {
                getLogger().log(Level.WARNING, "Could not mark bootstrap zone instance stopped", exception);
            }
            bootstrapZoneInstance = null;
        }

        getServer().getMessenger().unregisterOutgoingPluginChannel(this, TransferPluginMessage.CHANNEL);
        markBackendOfflineQuietly();
        closeDatabase();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        onlinePlayers.incrementAndGet();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        onlinePlayers.updateAndGet(current -> Math.max(0, current - 1));
    }

    public String backendId() {
        return backendId;
    }

    /** Compatibility alias for the original bootstrap API. */
    public String serverId() {
        return backendId;
    }

    private void sendHeartbeat() {
        int playerCount = onlinePlayers.get();
        try {
            backendRegistry.heartbeat(backendId, playerCount);
            BootstrapZoneInstance zone = bootstrapZoneInstance;
            if (zone != null) {
                zone.heartbeat(playerCount);
            }
        } catch (SQLException exception) {
            getLogger().log(Level.WARNING, "Backend/zone heartbeat failed", exception);
        }

        PaperSessionController controller = sessionController;
        if (controller != null) {
            controller.heartbeat();
        }
    }

    private void markBackendOfflineQuietly() {
        if (backendRegistry == null || backendId == null) {
            return;
        }
        try {
            backendRegistry.markOffline(backendId);
        } catch (SQLException exception) {
            getLogger().log(Level.WARNING, "Could not mark backend offline", exception);
        }
    }

    private void closeDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private static String requireBackendId() {
        String value = System.getenv("BACKEND_ID");
        if (value == null || value.isBlank()) {
            value = System.getenv("SERVER_ID");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("BACKEND_ID (or legacy SERVER_ID) must be set");
        }
        return value.trim();
    }
}
