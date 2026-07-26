package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliveryAuthority;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapCompletedEncounterRecoveryRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterHandoffQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterRecoveryRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterRecoveryService;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationReleaseRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReturnRouteRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPendingDeliveryAuthority;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPlayerStateOpenRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardSettlementRepository;
import io.github.kevinrabbe.minecraftserver.common.session.TransferRecoveryRepository;
import org.bukkit.scheduler.BukkitTask;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;

/** Builds and owns the Paper-facing Map reservation, gameplay, rewards, return routing, and persisted recovery lifecycle. */
final class PaperMapRuntime {
    private static final String ROUTE_RESOURCE = "/content/map-encounter-routes.json";
    private static final String ENCOUNTER_CONTENT_RESOURCE = "/content/map-encounters.json";
    private static final Duration ROUTE_HEARTBEAT_FRESHNESS = Duration.ofSeconds(15);
    private static final Duration NO_HANDOFF_GRACE = Duration.ofSeconds(30);
    private static final Duration TARGET_START_GRACE = Duration.ofSeconds(30);
    private static final int RECOVERY_BATCH_LIMIT = 50;
    private static final long RECOVERY_PERIOD_TICKS = 100L;

    private final PaperMapOpenService openService;
    private final PaperMapEncounterController encounterController;
    private final BukkitTask recoveryTask;

    private PaperMapRuntime(
            PaperMapOpenService openService,
            PaperMapEncounterController encounterController,
            BukkitTask recoveryTask
    ) {
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
        PaperMapEncounterContentCatalog content = PaperMapEncounterContentCatalog.loadResource(
                ENCOUNTER_CONTENT_RESOURCE,
                itemCatalog
        );
        MapAuthorityRepository maps = new MapAuthorityRepository(dataSource, itemCatalog);
        MapEncounterReservationRepository reservations = new MapEncounterReservationRepository(
                dataSource,
                ROUTE_HEARTBEAT_FRESHNESS
        );
        MapEncounterReservationReleaseRepository releases = new MapEncounterReservationReleaseRepository(dataSource);
        MapEncounterReturnRouteRepository returnRoutes = new MapEncounterReturnRouteRepository(dataSource);
        PaperMapOpenService openService = new PaperMapOpenService(
                plugin,
                sessions,
                maps,
                new MapPlayerStateOpenRepository(dataSource, itemCatalog, uniqueItemRemoval),
                reservations,
                routes,
                uniqueItemRemoval
        );

        PaperMapRewardResolver rewardResolver = new PaperMapRewardResolver(content);
        MapRewardSettlementRepository settlements = new MapRewardSettlementRepository(
                dataSource,
                itemCatalog,
                rewardResolver
        );
        CommodityDeliveryAuthority commodityDeliveries = new CommodityDeliveryAuthority(
                dataSource,
                definitionId -> {
                    var definition = itemCatalog.require(definitionId);
                    if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                        throw new MapAuthorityException("Map reward is not a commodity definition: " + definitionId);
                    }
                    return definition.definitionId();
                }
        );
        MapRewardFulfillmentRepository fulfillment = new MapRewardFulfillmentRepository(
                dataSource,
                commodityDeliveries,
                new PendingUniqueDeliveryRepository(dataSource, itemCatalog),
                new MapPendingDeliveryAuthority(dataSource, itemCatalog)
        );
        PaperMapCompletionService completion = new PaperMapCompletionService(
                plugin,
                maps,
                settlements,
                fulfillment,
                releases,
                new MapCompletedEncounterRecoveryRepository(dataSource)
        );

        MapEncounterRecoveryService abandonedRecovery = new MapEncounterRecoveryService(
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
                () -> recover(plugin, abandonedRecovery, completion),
                RECOVERY_PERIOD_TICKS,
                RECOVERY_PERIOD_TICKS
        );

        PaperMapEncounterController encounterController = null;
        if (bootstrapZoneInstance != null
                && routes.containsTarget(
                        bootstrapZoneInstance.zoneId(),
                        bootstrapZoneInstance.templateVersion()
                )) {
            PaperMapExterminationController gameplay = new PaperMapExterminationController(
                    plugin,
                    plugin.backendId(),
                    sessions,
                    maps,
                    releases,
                    returnRoutes,
                    content,
                    completion
            );
            encounterController = new PaperMapEncounterController(
                    plugin,
                    bootstrapZoneInstance,
                    identities,
                    new MapEncounterHandoffQueryRepository(dataSource),
                    maps,
                    gameplay
            );
            plugin.getServer().getPluginManager().registerEvents(gameplay, plugin);
            plugin.getServer().getPluginManager().registerEvents(encounterController, plugin);
            gameplay.start();
        }

        PaperLeaderboardRouterCommand.scheduleInstall(plugin, dataSource);
        return new PaperMapRuntime(openService, encounterController, recoveryTask);
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

    private static void recover(
            MinecraftServerPlugin plugin,
            MapEncounterRecoveryService abandonedRecovery,
            PaperMapCompletionService completion
    ) {
        try {
            int abandoned = abandonedRecovery.recoverOnce();
            int completed = completion.recoverCompletedOnce();
            if (abandoned > 0 || completed > 0) {
                plugin.getLogger().info(() -> "Recovered " + abandoned + " abandoned and " + completed
                        + " completed Map encounter(s)");
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Map encounter recovery pass failed", exception);
        }
    }
}
