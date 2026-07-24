package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
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
    private static final long CHECKPOINT_PERIOD_TICKS = 100L;
    private static final String ITEM_CATALOG_RESOURCE = "/content/items.json";

    private final AtomicInteger onlinePlayers = new AtomicInteger();

    private String backendId;
    private ItemCatalog itemCatalog;
    private Database database;
    private BackendRegistry backendRegistry;
    private PaperSessionController sessionController;
    private PaperPlayerItemRepresentationValidator itemRepresentationValidator;
    private BootstrapZoneInstance bootstrapZoneInstance;
    private BukkitTask heartbeatTask;
    private BukkitTask checkpointTask;

    @Override
    public void onEnable() {
        backendId = requireBackendId();

        try {
            itemCatalog = new ItemCatalogLoader().loadResource(ITEM_CATALOG_RESOURCE);
            PaperItemCatalogValidator.validate(itemCatalog);

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

            String currentZoneId = bootstrapZoneInstance == null ? null : bootstrapZoneInstance.zoneId();
            sessionController = new PaperSessionController(
                    this,
                    backendId,
                    currentZoneId,
                    database.dataSource()
            );
            itemRepresentationValidator = new PaperPlayerItemRepresentationValidator(
                    this,
                    database.dataSource(),
                    itemCatalog
            );
        } catch (RuntimeException | SQLException exception) {
            stopBootstrapZoneQuietly();
            markBackendOfflineQuietly();
            closeDatabase();
            itemRepresentationValidator = null;
            itemCatalog = null;
            throw new IllegalStateException("Failed to initialize persistent network foundation/content", exception);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(sessionController, this);
        getServer().getPluginManager().registerEvents(
                new PaperItemRepresentationGate(this, itemRepresentationValidator),
                this
        );
        getServer().getPluginManager().registerEvents(new FrozenPlayerMutationGuard(sessionController), this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, TransferPluginMessage.CHANNEL);

        PluginCommand devZone = Objects.requireNonNull(getCommand("devzone"), "devzone command missing from plugin.yml");
        devZone.setExecutor(new DevZoneCommand(sessionController));

        heartbeatTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                this::sendHeartbeat,
                HEARTBEAT_PERIOD_TICKS,
                HEARTBEAT_PERIOD_TICKS
        );
        checkpointTask = getServer().getScheduler().runTaskTimer(
                this,
                sessionController::checkpointOnlinePlayers,
                CHECKPOINT_PERIOD_TICKS,
                CHECKPOINT_PERIOD_TICKS
        );

        String zoneDescription = bootstrapZoneInstance == null
                ? "no bootstrap zone"
                : "bootstrap zone " + bootstrapZoneInstance.zoneId();
        int itemDefinitionCount = itemCatalog.size();
        getLogger().info(() -> "Started backend " + backendId + " with " + zoneDescription
                + " and " + itemDefinitionCount + " validated item definitions");
    }

    @Override
    public void onDisable() {
        if (checkpointTask != null) {
            checkpointTask.cancel();
            checkpointTask = null;
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }

        if (sessionController != null) {
            sessionController.shutdown();
            sessionController = null;
        }

        stopBootstrapZoneQuietly();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, TransferPluginMessage.CHANNEL);
        markBackendOfflineQuietly();
        closeDatabase();
        itemRepresentationValidator = null;
        itemCatalog = null;
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

    ItemCatalog itemCatalog() {
        if (itemCatalog == null) {
            throw new IllegalStateException("Item catalog is not initialized");
        }
        return itemCatalog;
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

    private void stopBootstrapZoneQuietly() {
        if (bootstrapZoneInstance == null) {
            return;
        }
        try {
            bootstrapZoneInstance.stop();
        } catch (SQLException exception) {
            getLogger().log(Level.WARNING, "Could not mark bootstrap zone instance stopped", exception);
        } finally {
            bootstrapZoneInstance = null;
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
