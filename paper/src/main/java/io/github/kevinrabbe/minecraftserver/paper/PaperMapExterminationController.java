package io.github.kevinrabbe.minecraftserver.paper;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterHandoffSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationReleaseRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReturnRoute;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReturnRouteRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunStatus;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/** Launch disposable runtime for authored solo Extermination Maps. Persistent run/reward state remains PostgreSQL-owned. */
final class PaperMapExterminationController implements Listener {
    private static final String RUN_ID_KEY_NAME = "map_run_id";
    private static final String PLAYER_DEATH_REASON = "map.player_death";
    private static final String PLAYER_QUIT_REASON = "map.player_disconnect";
    private static final String ENVIRONMENTAL_DEATH_REASON = "map.unowned_entity_death";
    private static final String MATERIALIZATION_FAILURE_REASON = "map.materialization_failed";
    private static final String SHUTDOWN_REASON = "map.backend_shutdown";
    private static final int RETURN_ATTEMPTS = 4;
    private static final long RETURN_RETRY_TICKS = 20L;

    private final MinecraftServerPlugin plugin;
    private final String backendId;
    private final PaperSessionController sessions;
    private final MapAuthorityRepository maps;
    private final MapEncounterReservationReleaseRepository releases;
    private final MapEncounterReturnRouteRepository returnRoutes;
    private final PaperMapEncounterContentCatalog content;
    private final PaperMapCompletionService completion;
    private final NamespacedKey runIdKey;
    private final ConcurrentHashMap<UUID, ActiveEncounter> activeByMinecraftUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> minecraftUuidByEntityUuid = new ConcurrentHashMap<>();
    private final Set<UUID> terminalInFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, String> pendingReturnByMinecraftUuid = new ConcurrentHashMap<>();

    PaperMapExterminationController(
            MinecraftServerPlugin plugin,
            String backendId,
            PaperSessionController sessions,
            MapAuthorityRepository maps,
            MapEncounterReservationReleaseRepository releases,
            MapEncounterReturnRouteRepository returnRoutes,
            PaperMapEncounterContentCatalog content,
            PaperMapCompletionService completion
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.backendId = requireText(backendId, "backendId");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.maps = Objects.requireNonNull(maps, "maps");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.returnRoutes = Objects.requireNonNull(returnRoutes, "returnRoutes");
        this.content = Objects.requireNonNull(content, "content");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.runIdKey = new NamespacedKey(plugin, RUN_ID_KEY_NAME);
    }

    void start() {
        removeLoadedUnmanagedHostiles();
    }

    void attach(
            UUID minecraftUuid,
            MapEncounterHandoffSnapshot handoff,
            MapRunSnapshot activeRun
    ) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Objects.requireNonNull(handoff, "handoff");
        Objects.requireNonNull(activeRun, "activeRun");
        if (activeRun.status() != MapRunStatus.ACTIVE || !activeRun.runId().equals(handoff.runId())) {
            throw new MapAuthorityException("Map handoff is not attached to the expected ACTIVE run: " + handoff.runId());
        }
        PaperMapEncounterDefinition definition = content.require(activeRun.definition());
        MapEncounterReturnRoute returnRoute = returnRoutes.load(activeRun.runId());
        if (!returnRoute.playerId().equals(handoff.playerId())) {
            throw new MapAuthorityException("Map return route player does not match handoff: " + handoff.runId());
        }
        runMain(() -> materialize(minecraftUuid, handoff, activeRun, definition, returnRoute.sourceZoneId()));
    }

    /** Routes a reconnect that reached this disposable backend after its exact run already became terminal. */
    boolean evacuateTerminalReconnect(UUID minecraftUuid, UUID playerId) throws SQLException {
        Optional<MapEncounterReturnRoute> route = returnRoutes.findLatestTerminalForPlayerBackend(playerId, backendId);
        if (route.isEmpty()) {
            return false;
        }
        scheduleReturn(minecraftUuid, route.orElseThrow().sourceZoneId(), 0);
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster)) {
            return;
        }
        String runId = event.getEntity().getPersistentDataContainer().get(runIdKey, PersistentDataType.STRING);
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM || runId == null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAdded(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Monster monster)) {
            return;
        }
        String rawRunId = monster.getPersistentDataContainer().get(runIdKey, PersistentDataType.STRING);
        if (rawRunId == null) {
            plugin.getServer().getScheduler().runTask(plugin, monster::remove);
            return;
        }
        UUID entityUuid = monster.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (monster.isValid() && !minecraftUuidByEntityUuid.containsKey(entityUuid)) {
                monster.remove();
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDeath(EntityDeathEvent event) {
        String rawRunId = event.getEntity().getPersistentDataContainer().get(runIdKey, PersistentDataType.STRING);
        if (rawRunId == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);

        final UUID runId;
        try {
            runId = UUID.fromString(rawRunId);
        } catch (IllegalArgumentException exception) {
            return;
        }
        UUID entityUuid = event.getEntity().getUniqueId();
        UUID minecraftUuid = minecraftUuidByEntityUuid.remove(entityUuid);
        if (minecraftUuid == null) {
            return;
        }
        ActiveEncounter active = activeByMinecraftUuid.get(minecraftUuid);
        if (active == null || !active.runId.equals(runId) || !active.entityUuids.remove(entityUuid)) {
            return;
        }

        Player killer = event.getEntity().getKiller();
        if (killer == null || !killer.getUniqueId().equals(minecraftUuid)) {
            beginFailure(active, ENVIRONMENTAL_DEATH_REASON, true);
            return;
        }
        if (active.entityUuids.isEmpty()) {
            beginCompletion(active);
            return;
        }
        killer.sendMessage(Component.text(
                "Map Extermination: " + active.entityUuids.size() + " target(s) remaining."
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        ActiveEncounter active = activeByMinecraftUuid.get(event.getEntity().getUniqueId());
        if (active != null) {
            beginFailure(active, PLAYER_DEATH_REASON, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        String returnZone = pendingReturnByMinecraftUuid.remove(minecraftUuid);
        if (returnZone != null) {
            plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> scheduleReturn(minecraftUuid, returnZone, 0),
                    1L
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        pendingReturnByMinecraftUuid.remove(minecraftUuid);
        ActiveEncounter active = activeByMinecraftUuid.get(minecraftUuid);
        if (active != null) {
            beginFailure(active, PLAYER_QUIT_REASON, false);
        }
    }

    void shutdown() {
        List<ActiveEncounter> active = List.copyOf(activeByMinecraftUuid.values());
        activeByMinecraftUuid.clear();
        pendingReturnByMinecraftUuid.clear();
        for (ActiveEncounter encounter : active) {
            if (!terminalInFlight.add(encounter.runId)) {
                continue;
            }
            cleanupEntities(encounter);
            try {
                failAndRelease(encounter, SHUTDOWN_REASON);
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not fail Map encounter during shutdown", exception);
            } finally {
                terminalInFlight.remove(encounter.runId);
            }
        }
    }

    private void materialize(
            UUID minecraftUuid,
            MapEncounterHandoffSnapshot handoff,
            MapRunSnapshot run,
            PaperMapEncounterDefinition definition,
            String returnZone
    ) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline()) {
            runAsync(() -> failDetached(handoff, returnZone, minecraftUuid, PLAYER_QUIT_REASON, false));
            return;
        }

        int requiredKills = definition.requiredKills(run.definition().difficulty().value());
        ActiveEncounter active = new ActiveEncounter(
                minecraftUuid,
                run.runId(),
                handoff.reservationId(),
                handoff.playerId(),
                returnZone,
                Instant.now(),
                ConcurrentHashMap.newKeySet()
        );
        ArrayList<LivingEntity> spawned = new ArrayList<>(requiredKills);
        try {
            World world = player.getWorld();
            Location center = player.getLocation().clone();
            SplittableRandom random = new SplittableRandom(run.definition().generationSeed());
            for (int index = 0; index < requiredKills; index++) {
                Location location = spawnLocation(center, definition.spawnRadius(), index, requiredKills, random);
                Entity entity = world.spawnEntity(
                        location,
                        definition.entityType(),
                        CreatureSpawnEvent.SpawnReason.CUSTOM,
                        spawnedEntity -> configureSpawnedEntity(spawnedEntity, run, definition)
                );
                if (!(entity instanceof LivingEntity living)) {
                    entity.remove();
                    throw new IllegalStateException("Map encounter entity type did not materialize as LivingEntity");
                }
                spawned.add(living);
                active.entityUuids.add(living.getUniqueId());
                minecraftUuidByEntityUuid.put(living.getUniqueId(), minecraftUuid);
            }

            ActiveEncounter previous = activeByMinecraftUuid.putIfAbsent(minecraftUuid, active);
            if (previous != null) {
                throw new MapAuthorityException("Player already owns a live Map encounter on this backend");
            }
            for (LivingEntity living : spawned) {
                if (living.isValid()) {
                    living.setInvulnerable(false);
                }
            }
            player.sendMessage(Component.text(
                    "Map Extermination started: defeat " + requiredKills + " "
                            + definition.enemyFamilyId() + " target(s)."
            ));
        } catch (RuntimeException exception) {
            for (LivingEntity living : spawned) {
                minecraftUuidByEntityUuid.remove(living.getUniqueId(), minecraftUuid);
                if (living.isValid()) {
                    living.remove();
                }
            }
            activeByMinecraftUuid.remove(minecraftUuid, active);
            plugin.getLogger().log(Level.WARNING, "Could not materialize Map encounter " + run.runId(), exception);
            runAsync(() -> failDetached(
                    handoff,
                    returnZone,
                    minecraftUuid,
                    MATERIALIZATION_FAILURE_REASON,
                    true
            ));
        }
    }

    private void configureSpawnedEntity(
            Entity entity,
            MapRunSnapshot run,
            PaperMapEncounterDefinition definition
    ) {
        entity.getPersistentDataContainer().set(runIdKey, PersistentDataType.STRING, run.runId().toString());
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        living.setPersistent(true);
        living.setInvulnerable(true);
        if (living instanceof Mob mob) {
            mob.setRemoveWhenFarAway(false);
        }
        int difficulty = run.definition().difficulty().value();
        scaleAttribute(living, Attribute.MAX_HEALTH, definition.healthMultiplier(difficulty));
        scaleAttribute(living, Attribute.ATTACK_DAMAGE, definition.damageMultiplier(difficulty));
        AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            living.setHealth(Math.max(1.0, maxHealth.getValue()));
        }
    }

    private static void scaleAttribute(LivingEntity entity, Attribute attribute, double multiplier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        double scaled = instance.getBaseValue() * multiplier;
        if (Double.isFinite(scaled) && scaled > 0.0) {
            instance.setBaseValue(scaled);
        }
    }

    private static Location spawnLocation(
            Location center,
            double maxRadius,
            int index,
            int count,
            SplittableRandom random
    ) {
        double baseAngle = (Math.PI * 2.0 * index) / Math.max(1, count);
        double angle = baseAngle + random.nextDouble(-0.20, 0.20);
        double minRadius = Math.min(3.0, maxRadius);
        double radius = minRadius + random.nextDouble() * Math.max(0.0, maxRadius - minRadius);
        return center.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
    }

    private void beginCompletion(ActiveEncounter active) {
        if (!terminalInFlight.add(active.runId) || !activeByMinecraftUuid.remove(active.minecraftUuid, active)) {
            return;
        }
        cleanupEntities(active);
        long elapsedMillis = Math.max(1L, Duration.between(active.startedAt, Instant.now()).toMillis());
        runAsync(() -> {
            try {
                completion.completeAndFulfill(active.runId, active.reservationId, elapsedMillis);
                scheduleReturn(active.minecraftUuid, active.returnZoneId, 0);
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not settle completed Map encounter " + active.runId, exception);
                recoverOrFailAfterCompletionError(active);
            } finally {
                terminalInFlight.remove(active.runId);
            }
        });
    }

    private void beginFailure(ActiveEncounter active, String reason, boolean returnWhenPossible) {
        if (!terminalInFlight.add(active.runId) || !activeByMinecraftUuid.remove(active.minecraftUuid, active)) {
            return;
        }
        cleanupEntities(active);
        runAsync(() -> {
            try {
                failAndRelease(active, reason);
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not fail Map encounter " + active.runId, exception);
            } finally {
                terminalInFlight.remove(active.runId);
                if (returnWhenPossible) {
                    scheduleReturn(active.minecraftUuid, active.returnZoneId, 0);
                }
            }
        });
    }

    private void failDetached(
            MapEncounterHandoffSnapshot handoff,
            String returnZone,
            UUID minecraftUuid,
            String reason,
            boolean returnWhenPossible
    ) {
        if (!terminalInFlight.add(handoff.runId())) {
            return;
        }
        try {
            ActiveEncounter detached = new ActiveEncounter(
                    minecraftUuid,
                    handoff.runId(),
                    handoff.reservationId(),
                    handoff.playerId(),
                    returnZone,
                    Instant.now(),
                    ConcurrentHashMap.newKeySet()
            );
            failAndRelease(detached, reason);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not fail detached Map encounter " + handoff.runId(), exception);
        } finally {
            terminalInFlight.remove(handoff.runId());
            if (returnWhenPossible) {
                scheduleReturn(minecraftUuid, returnZone, 0);
            }
        }
    }

    private void failAndRelease(ActiveEncounter active, String reason) throws SQLException {
        MapRunSnapshot current = maps.loadRun(active.runId);
        if (current.status() == MapRunStatus.CREATED || current.status() == MapRunStatus.ACTIVE) {
            maps.failRun(
                    deterministicFailureOperation(reason, active.runId),
                    active.runId,
                    current.stateVersion(),
                    reason
            );
            current = maps.loadRun(active.runId);
        }
        if (current.status() == MapRunStatus.FAILED) {
            releases.releaseTerminalRun(active.reservationId, active.runId);
        }
    }

    private void recoverOrFailAfterCompletionError(ActiveEncounter active) {
        try {
            MapRunSnapshot current = maps.loadRun(active.runId);
            if (current.status() == MapRunStatus.COMPLETED) {
                scheduleReturn(active.minecraftUuid, active.returnZoneId, 0);
                return;
            }
            if (current.status() == MapRunStatus.ACTIVE || current.status() == MapRunStatus.CREATED) {
                failAndRelease(active, MATERIALIZATION_FAILURE_REASON);
            }
        } catch (SQLException | RuntimeException recoveryFailure) {
            plugin.getLogger().log(Level.WARNING, "Could not recover Map after completion error " + active.runId, recoveryFailure);
        } finally {
            scheduleReturn(active.minecraftUuid, active.returnZoneId, 0);
        }
    }

    private void cleanupEntities(ActiveEncounter active) {
        for (UUID entityUuid : List.copyOf(active.entityUuids)) {
            minecraftUuidByEntityUuid.remove(entityUuid, active.minecraftUuid);
            Entity entity = plugin.getServer().getEntity(entityUuid);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
        active.entityUuids.clear();
    }

    private void scheduleReturn(UUID minecraftUuid, String returnZone, int attempt) {
        if (!plugin.isEnabled() || attempt >= RETURN_ATTEMPTS) {
            return;
        }
        runMain(() -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            if (player.isDead()) {
                pendingReturnByMinecraftUuid.put(minecraftUuid, returnZone);
                return;
            }
            boolean started = sessions.requestZoneTransfer(player, returnZone);
            if (!started && attempt + 1 < RETURN_ATTEMPTS) {
                plugin.getServer().getScheduler().runTaskLater(
                        plugin,
                        () -> scheduleReturn(minecraftUuid, returnZone, attempt + 1),
                        RETURN_RETRY_TICKS
                );
            }
        });
    }

    private void removeLoadedUnmanagedHostiles() {
        for (World world : plugin.getServer().getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) {
                if (living instanceof Monster) {
                    living.remove();
                }
            }
        }
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule Map encounter persistence work", exception);
        }
    }

    private void runMain(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private static UUID deterministicFailureOperation(String reason, UUID runId) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:map:fail:" + reason + ":" + runId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static final class ActiveEncounter {
        private final UUID minecraftUuid;
        private final UUID runId;
        private final UUID reservationId;
        private final UUID playerId;
        private final String returnZoneId;
        private final Instant startedAt;
        private final Set<UUID> entityUuids;

        private ActiveEncounter(
                UUID minecraftUuid,
                UUID runId,
                UUID reservationId,
                UUID playerId,
                String returnZoneId,
                Instant startedAt,
                Set<UUID> entityUuids
        ) {
            this.minecraftUuid = Objects.requireNonNull(minecraftUuid, "minecraftUuid");
            this.runId = Objects.requireNonNull(runId, "runId");
            this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.returnZoneId = requireText(returnZoneId, "returnZoneId");
            this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
            this.entityUuids = Objects.requireNonNull(entityUuids, "entityUuids");
        }
    }
}
