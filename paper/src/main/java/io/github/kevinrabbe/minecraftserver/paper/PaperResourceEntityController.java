package io.github.kevinrabbe.minecraftserver.paper;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntityKillClaim;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntitySpawnRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntitySpawnSnapshot;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntitySpawnStatus;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceGatheringService;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceException;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceRepository;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Paper lifecycle for ordinary authorized PvE mobs backed by renewable source cycles.
 *
 * <p>Vanilla hostile spawning/drops are not value authority on a managed PvE backend. One source cycle reserves one
 * spawn ID, that ID is persisted on exactly one runtime entity, and a player kill can settle only through the existing
 * resource-harvest authority. Stale/duplicated entities therefore cannot consume a later cycle.</p>
 */
final class PaperResourceEntityController implements Listener {
    private static final long RECONCILE_PERIOD_TICKS = 20L;
    private static final long KILL_RETRY_DELAY_TICKS = 10L;
    private static final int MAX_KILL_ATTEMPTS = 6;
    private static final String KILL_REASON = "resource.entity_kill";

    private final JavaPlugin plugin;
    private final String backendId;
    private final PaperResourceSessionResolver sessionResolver;
    private final PaperCommodityDeliveryController commodityDeliveries;
    private final ResourceSourceRepository sourceRepository;
    private final ResourceEntitySpawnRepository entitySpawns;
    private final ResourceGatheringService gathering;
    private final NamespacedKey spawnIdKey;
    private final NamespacedKey bountySummonIdKey;
    private final Map<UUID, ManagedSource> sourcesById;
    private final AtomicBoolean reconcileInFlight = new AtomicBoolean();

    private BukkitTask reconcileTask;

    PaperResourceEntityController(
            JavaPlugin plugin,
            String backendId,
            BootstrapZoneInstance zoneInstance,
            PaperResourceEntityPlacementCatalog placements,
            ResourceSourceRepository sourceRepository,
            ResourceEntitySpawnRepository entitySpawns,
            ResourceGatheringService gathering,
            PaperResourceSessionResolver sessionResolver,
            PaperCommodityDeliveryController commodityDeliveries
    ) throws SQLException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        this.backendId = backendId.trim();
        this.sourceRepository = Objects.requireNonNull(sourceRepository, "sourceRepository");
        this.entitySpawns = Objects.requireNonNull(entitySpawns, "entitySpawns");
        this.gathering = Objects.requireNonNull(gathering, "gathering");
        this.sessionResolver = Objects.requireNonNull(sessionResolver, "sessionResolver");
        this.commodityDeliveries = Objects.requireNonNull(commodityDeliveries, "commodityDeliveries");
        this.spawnIdKey = new NamespacedKey(plugin, "resource_spawn_id");
        this.bountySummonIdKey = new NamespacedKey(plugin, PaperBountyBossController.SUMMON_ID_KEY_NAME);
        Objects.requireNonNull(zoneInstance, "zoneInstance");
        Objects.requireNonNull(placements, "placements");

        HashMap<UUID, ManagedSource> configured = new HashMap<>();
        for (PaperResourceEntityPlacement placement : placements.forZone(
                zoneInstance.zoneId(), zoneInstance.templateVersion()
        )) {
            World world = plugin.getServer().getWorld(placement.worldName());
            if (world == null) {
                throw new IllegalStateException(
                        "Configured entity source world is not loaded: " + placement.worldName()
                );
            }
            UUID sourceId = sourceRepository.ensureSource(
                    zoneInstance.instanceId(), placement.sourceKey(), placement.definitionId()
            ).sourceId();
            entitySpawns.ensureEntitySource(sourceId);
            ManagedSource previous = configured.put(sourceId, new ManagedSource(sourceId, placement));
            if (previous != null) {
                throw new IllegalStateException("Duplicate managed entity source ID: " + sourceId);
            }
        }
        this.sourcesById = Map.copyOf(configured);
    }

    int managedSourceCount() {
        return sourcesById.size();
    }

    void start() {
        if (sourcesById.isEmpty() || reconcileTask != null) {
            return;
        }
        reconcileLoadedEntities();
        reconcileTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::reconcileAll,
                1L,
                RECONCILE_PERIOD_TICKS
        );
    }

    void stop() {
        if (reconcileTask != null) {
            reconcileTask.cancel();
            reconcileTask = null;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (sourcesById.isEmpty() || !(event.getEntity() instanceof Monster)) {
            return;
        }
        if (isBountyBoss(event.getEntity())) {
            if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
                event.setCancelled(true);
            }
            return;
        }
        UUID spawnId = readSpawnId(event.getEntity());
        boolean managedCustom = event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM && spawnId != null;
        if (!managedCustom) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAdded(EntityAddToWorldEvent event) {
        if (sourcesById.isEmpty() || !(event.getEntity() instanceof Monster monster)) {
            return;
        }
        if (isBountyBoss(monster)) {
            return;
        }
        UUID spawnId = readSpawnId(monster);
        if (spawnId == null) {
            // Old/natural hostile entities loaded from world bytes are representation noise on an authorized PvE backend.
            plugin.getServer().getScheduler().runTask(plugin, monster::remove);
            return;
        }
        validateLoadedManagedEntity(spawnId, monster.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDeath(EntityDeathEvent event) {
        if (sourcesById.isEmpty() || !(event.getEntity() instanceof Monster monster)) {
            return;
        }

        // Vanilla loot/XP is never authoritative in this managed ordinary-PvE context.
        event.getDrops().clear();
        event.setDroppedExp(0);

        UUID spawnId = readSpawnId(monster);
        if (spawnId == null) {
            return;
        }
        Player killer = monster.getKiller();
        if (killer == null) {
            resolveWithoutReward(spawnId, monster.getUniqueId());
            return;
        }
        settlePlayerKill(killer.getUniqueId(), spawnId, monster.getUniqueId(), 1);
    }

    private void reconcileAll() {
        if (!plugin.isEnabled() || !reconcileInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            for (ManagedSource source : sourcesById.values()) {
                try {
                    Optional<UUID> expiredEntity = entitySpawns.expireStaleSpawn(source.sourceId());
                    expiredEntity.ifPresent(entityUuid -> removeManagedEntity(source.placement().worldName(), entityUuid));

                    Optional<ResourceEntitySpawnSnapshot> reservation = entitySpawns.reserveSpawn(
                            source.sourceId(), source.placement().pendingLease()
                    );
                    reservation.ifPresent(spawn -> spawnReserved(source, spawn));
                } catch (SQLException | RuntimeException exception) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Could not reconcile entity source " + source.sourceId(),
                            exception
                    );
                }
            }
        } finally {
            reconcileInFlight.set(false);
        }
    }

    private void spawnReserved(ManagedSource source, ResourceEntitySpawnSnapshot reservation) {
        runOnMainThread(() -> {
            World world = plugin.getServer().getWorld(source.placement().worldName());
            if (world == null) {
                cancelPending(reservation.spawnId());
                return;
            }
            Location location = new Location(
                    world,
                    source.placement().x(),
                    source.placement().y(),
                    source.placement().z()
            );
            final Entity entity;
            try {
                entity = world.spawnEntity(
                        location,
                        source.placement().entityType(),
                        CreatureSpawnEvent.SpawnReason.CUSTOM,
                        spawned -> {
                            spawned.getPersistentDataContainer().set(
                                    spawnIdKey,
                                    PersistentDataType.STRING,
                                    reservation.spawnId().toString()
                            );
                            if (spawned instanceof LivingEntity living) {
                                living.setRemoveWhenFarAway(false);
                                living.setCanPickupItems(false);
                            }
                        }
                );
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not materialize reserved entity spawn " + reservation.spawnId(),
                        exception
                );
                cancelPending(reservation.spawnId());
                return;
            }
            if (!(entity instanceof Monster)) {
                entity.remove();
                plugin.getLogger().warning(
                        "Configured ordinary PvE entity is not a Monster: " + source.placement().entityType()
                );
                cancelPending(reservation.spawnId());
                return;
            }
            confirmSpawn(source, reservation.spawnId(), entity.getUniqueId());
        });
    }

    private void confirmSpawn(ManagedSource source, UUID spawnId, UUID entityUuid) {
        runAsync(() -> {
            try {
                entitySpawns.confirmSpawn(spawnId, entityUuid, source.placement().activeLifetime());
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not confirm managed entity spawn " + spawnId, exception);
                removeManagedEntity(source.placement().worldName(), entityUuid);
                cancelPending(spawnId);
            }
        });
    }

    private void validateLoadedManagedEntity(UUID spawnId, UUID entityUuid) {
        runAsync(() -> {
            try {
                Optional<ResourceEntitySpawnSnapshot> loaded = entitySpawns.loadSpawn(spawnId);
                if (loaded.isEmpty()) {
                    removeEntityFromAnyWorld(entityUuid);
                    return;
                }
                ResourceEntitySpawnSnapshot spawn = loaded.orElseThrow();
                ManagedSource source = sourcesById.get(spawn.sourceId());
                if (source == null || spawn.status() == ResourceEntitySpawnStatus.KILLED
                        || spawn.status() == ResourceEntitySpawnStatus.CANCELLED
                        || spawn.status() == ResourceEntitySpawnStatus.EXPIRED) {
                    removeEntityFromAnyWorld(entityUuid);
                    return;
                }
                if (spawn.status() == ResourceEntitySpawnStatus.PENDING) {
                    entitySpawns.confirmSpawn(spawnId, entityUuid, source.placement().activeLifetime());
                    return;
                }
                if (spawn.status() == ResourceEntitySpawnStatus.ACTIVE
                        && !entityUuid.equals(spawn.entityUuid())) {
                    removeEntityFromAnyWorld(entityUuid);
                }
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not validate loaded managed entity " + spawnId, exception);
                removeEntityFromAnyWorld(entityUuid);
            }
        });
    }

    private void settlePlayerKill(UUID killerMinecraftUuid, UUID spawnId, UUID entityUuid, int attempt) {
        runAsync(() -> {
            try {
                Optional<PaperResourceSessionResolver.ResourceSessionHint> resolved = sessionResolver.resolve(
                        killerMinecraftUuid
                );
                if (resolved.isEmpty()) {
                    retryOrResolveWithoutReward(killerMinecraftUuid, spawnId, entityUuid, attempt, null);
                    return;
                }
                PaperResourceSessionResolver.ResourceSessionHint session = resolved.orElseThrow();
                ResourceEntityKillClaim claim = entitySpawns.prepareKillClaim(spawnId, entityUuid);
                gathering.harvestAndFulfill(
                        claim.operationId(),
                        session.sessionId(),
                        backendId,
                        session.stateVersion(),
                        claim.sourceId(),
                        KILL_REASON
                );
                commodityDeliveries.requestDrain(killerMinecraftUuid);
            } catch (SQLException | ResourceSourceException exception) {
                retryOrResolveWithoutReward(killerMinecraftUuid, spawnId, entityUuid, attempt, exception);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Managed entity kill failed closed", exception);
                retryOrResolveWithoutReward(killerMinecraftUuid, spawnId, entityUuid, attempt, exception);
            }
        });
    }

    private void retryOrResolveWithoutReward(
            UUID killerMinecraftUuid,
            UUID spawnId,
            UUID entityUuid,
            int attempt,
            Throwable failure
    ) {
        if (attempt < MAX_KILL_ATTEMPTS && plugin.isEnabled()) {
            runOnMainThreadLater(
                    () -> settlePlayerKill(killerMinecraftUuid, spawnId, entityUuid, attempt + 1),
                    KILL_RETRY_DELAY_TICKS
            );
            return;
        }
        if (failure != null) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Managed entity kill could not settle after " + attempt + " attempts; resolving without reward",
                    failure
            );
        }
        try {
            entitySpawns.resolveWithoutReward(spawnId, entityUuid);
        } catch (SQLException | RuntimeException resolutionFailure) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not release failed managed entity kill " + spawnId
                            + "; lease expiry remains the recovery boundary",
                    resolutionFailure
            );
        }
    }

    private void resolveWithoutReward(UUID spawnId, UUID entityUuid) {
        runAsync(() -> {
            try {
                entitySpawns.resolveWithoutReward(spawnId, entityUuid);
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not resolve managed entity death without reward; lease expiry will recover it",
                        exception
                );
            }
        });
    }

    private void cancelPending(UUID spawnId) {
        runAsync(() -> {
            try {
                entitySpawns.cancelPending(spawnId);
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not cancel pending managed entity spawn; lease expiry will recover it",
                        exception
                );
            }
        });
    }

    private void reconcileLoadedEntities() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Monster)) {
                    continue;
                }
                UUID spawnId = readSpawnId(entity);
                if (spawnId != null) {
                    validateLoadedManagedEntity(spawnId, entity.getUniqueId());
                }
            }
        }
    }

    private boolean isBountyBoss(Entity entity) {
        try {
            return entity.getPersistentDataContainer().has(bountySummonIdKey, PersistentDataType.STRING);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().log(Level.WARNING, "Bounty boss summon tag has invalid type", exception);
            return false;
        }
    }

    private UUID readSpawnId(Entity entity) {
        String raw;
        try {
            raw = entity.getPersistentDataContainer().get(spawnIdKey, PersistentDataType.STRING);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().log(Level.WARNING, "Managed entity spawn tag has invalid type", exception);
            return null;
        }
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Managed entity contains malformed spawn ID: " + raw);
            return null;
        }
    }

    private void removeManagedEntity(String worldName, UUID entityUuid) {
        runOnMainThread(() -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                return;
            }
            for (Entity entity : world.getEntities()) {
                if (entityUuid.equals(entity.getUniqueId())) {
                    entity.remove();
                    return;
                }
            }
        });
    }

    private void removeEntityFromAnyWorld(UUID entityUuid) {
        runOnMainThread(() -> {
            for (World world : plugin.getServer().getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entityUuid.equals(entity.getUniqueId())) {
                        entity.remove();
                        return;
                    }
                }
            }
        });
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule managed entity work", exception);
        }
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

    private record ManagedSource(UUID sourceId, PaperResourceEntityPlacement placement) {
        private ManagedSource {
            sourceId = Objects.requireNonNull(sourceId, "sourceId");
            placement = Objects.requireNonNull(placement, "placement");
        }
    }
}
