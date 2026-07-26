package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterHandoffQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterRecoveryRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterRecoveryService;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationReleaseRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPlayerStateOpenRepository;
import io.github.kevinrabbe.minecraftserver.common.session.TransferRecoveryRepository;
import org.bukkit.scheduler.BukkitTask;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;

/** Builds and owns the Paper-facing Map reservation, handoff, target-attachment, and persisted recovery lifecycle. */
final class PaperMapRuntime {
    private static final String ROUTE_RESOURCE = "/content/map-encounter-routes.json";
    private static final Duration ROUTE_HEARTBEAT_FRESHNESS = Duration.ofSeconds(15);
    private static final Duration NO_HANDOFF_GRACE = Duration.ofSeconds(30);
    private static final Duration TARGET_START_GRACE = Duration.ofSeconds(30);
    private static final int RECOVERY_BATCH_LIMIT = 50;
    private static final long RECOVERY_PERIOD_TICKS = 100L;

    private final MinecraftServerPlugin plugin;
    private final PaperMapOpenService openService;
    private final PaperMapEncounterController encounterController;
    private final BukkitTask recoveryTask;

    private PaperMapRuntime(
            MinecraftServerPlugin plugin,
            PaperMapOpenService openService,
            PaperMapEncounterController encounterController,
            BukkitTask recoveryTask
    ) {
        this.plugin = plugin;
        this.openService = openService;
        this.encounterController = encounterController;
        this.recoveryTask = recoveryTask;
    }

    static PaperMapRuntime start(
            MinecraftServerPlugin plugin,
            BootstrapZoneInstance bootstrapZoneInstance,
            DataSource dataSource,
            PaperSessionController sessions,
            PaperPlayerIdentityResolver identities,
            ItemCatalog itemCatalog,
            PaperUniqueItemStateRemovalMutator uniqueItemRemoval
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(identities, "identities");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        Objects.requireNonNull(uniqueItemRemoval, "uniqueItemRemoval");

        PaperMapEncounterRouteCatalog routes = PaperMapEncounterRouteCatalog.loadResource(ROUTE_RESOURCE);
        MapAuthorityRepository maps = new MapAuthorityRepository(dataSource, itemCatalog);
        MapEncounterReservationRepository reservations = new MapEncounterReservationRepository(
                dataSource,
                ROUTE_HEARTBEAT_FRESHNESS
        );
        MapEncounterReservationReleaseRepository releases = new MapEncounterReservationReleaseRepository(dataSource);
        PaperMapOpenService openService = new PaperMapOpenService(
                plugin,
                sessions,
                maps,
                new MapPlayerStateOpenRepository(dataSource, itemCatalog, uniqueItemRemoval),
                reservations,
                routes,
                uniqueItemRemoval
        );

        MapEncounterRecoveryService recovery = new MapEncounterRecoveryService(
                new MapEncounterRecoveryRepository(dataSource),
                maps,
                releases,
                new TransferRecoveryRepository(dataSource),
                NO_HANDOFF_GRACE,
                TARGET_START_GRACE,
                RECOVERY_BATCH_LIMIT
        );
        BukkitTask recoveryTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                () -> recover(plugin, recovery),
                RECOVERY_PERIOD_TICKS,
                RECOVERY_PERIOD_TICKS
        );

        PaperMapEncounterController encounterController = null;
        if (bootstrapZoneInstance != null
                && routes.containsTarget(
                        bootstrapZoneInstance.zoneId(),
                        bootstrapZoneInstance.templateVersion()
                )) {
            encounterController = new PaperMapEncounterController(
                    plugin,
                    bootstrapZoneInstance,
                    identities,
                    new MapEncounterHandoffQueryRepository(dataSource),
                    maps,
                    releases
            );
            plugin.getServer().getPluginManager().registerEvents(encounterController, plugin);
        }

        return new PaperMapRuntime(plugin, openService, encounterController, recoveryTask);
    }

    PaperMapOpenService openService() {
        return openService;
    }

    void shutdown() {
        recoveryTask.cancel();
        if (encounterController != null) {
            encounterController.shutdown();
        }
    }

    private static void recover(MinecraftServerPlugin plugin, MapEncounterRecoveryService recovery) {
        try {
            int recovered = recovery.recoverOnce();
            if (recovered > 0) {
                plugin.getLogger().info(() -> "Recovered " + recovered + " abandoned Map encounter(s)");
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Map encounter recovery pass failed", exception);
        }
    }
}
