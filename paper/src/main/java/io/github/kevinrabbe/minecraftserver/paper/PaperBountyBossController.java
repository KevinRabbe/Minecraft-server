package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyBossMaterializationRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyCompletionResult;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContentCatalog;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContractSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyException;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountySummonLeaseResult;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountySummonRecoveryRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountySummonSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountySummonStatus;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyTierDefinition;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

/**
 * Disposable Paper representation of leased bounty summons.
 *
 * <p>Persistent value remains in PostgreSQL. A live entity is invulnerable until its UUID is durably bound to the
 * ACTIVE summon. Boss attempts are not resumed across backend restart; graceful shutdown fails live attempts and stale
 * tagged entities are discarded on startup. This matches the launch rule that failed attempts consume the summon.</p>
 */
final class PaperBountyBossController implements Listener {
    static final String SUMMON_ID_KEY_NAME = "bounty_summon_id";

    private static final long MAINTENANCE_PERIOD_TICKS = 100L;
    private static final Duration RECOVERY_READY_GRACE = Duration.ofSeconds(30);
    private static final int RECOVERY_LIMIT = 50;
    private static final String PREPARE_REASON = "bounty.paper_boss_prepare";
    private static final String CLAIM_REASON = "bounty.paper_boss_claim";
    private static final String HEARTBEAT_REASON = "bounty.paper_boss_heartbeat";
    private static final String RECOVERY_CLAIM_REASON = "bounty.paper_boss_recovery_claim";
    private static final String COMPLETE_REASON = "bounty.paper_boss_defeated";
    private static final String FAIL_REASON = "bounty.paper_boss_failed";

    private final JavaPlugin plugin;
    private final String backendId;
    private final BootstrapZoneInstance bootstrapZone;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final BountyContentCatalog content;
    private final PaperBountyBossPlacementCatalog placements;
    private final BountyRepository bounties;
    private final BountyBossMaterializationRepository materializations;
    private final BountySummonRecoveryRepository recovery;
    private final NamespacedKey summonIdKey;
    private final Map<UUID, LiveBoss> liveBySummon = new ConcurrentHashMap<>();
    private final Map<String, UUID> busyBossDefinitions = new ConcurrentHashMap<>();
    private final Set<UUID> terminalInFlight = ConcurrentHashMap.newKeySet();

    private BukkitTask maintenanceTask;

    PaperBountyBossController(
            MinecraftServerPlugin plugin,
            String backendId,
            BootstrapZoneInstance bootstrapZone,
            PaperPlayerIdentityResolver playerIdentities,
            BountyContentCatalog content,
            PaperBountyBossPlacementCatalog placements,
            BountyRepository bounties,
            BountyBossMaterializationRepository materializations,
            BountySummonRecoveryRepository recovery
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.backendId = requireText(backendId, "backendId");
        this.bootstrapZone = Objects.requireNonNull(bootstrapZone, "bootstrapZone");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.content = Objects.requireNonNull(content, "content");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.bounties = Objects.requireNonNull(bounties, "bounties");
        this.materializations = Objects.requireNonNull(materializations, "materializations");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.summonIdKey = new NamespacedKey(plugin, SUMMON_ID_KEY_NAME);
    }

    void start() {
        if (maintenanceTask != null) return;
        removeLoadedStaleBosses();
        maintenanceTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::maintain,
                1L,
                MAINTENANCE_PERIOD_TICKS
        );
    }

    void stop() {
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }
        ArrayList<LiveBoss> active = new ArrayList<>(liveBySummon.values());
        liveBySummon.clear();
        busyBossDefinitions.clear();
        terminalInFlight.clear();
        for (LiveBoss live : active) {
            LivingEntity entity = liveEntity(live.entityUuid());
            if (entity != null) entity.remove();
            try {
                failWithCurrentVersion(live.summonId());
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Could not fail live bounty boss during shutdown; lease expiry remains the recovery fence",
                        exception
                );
            }
        }
    }

    void requestSummon(UUID minecraftUuid, UUID playerId, UUID contractId) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(contractId, "contractId");
        runAsync(() -> prepareAndClaim(minecraftUuid, playerId, contractId));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBossDeath(EntityDeathEvent event) {
        String raw = event.getEntity().getPersistentDataContainer().get(summonIdKey, PersistentDataType.STRING);
        if (raw == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);

        final UUID summonId;
        try {
            summonId = UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            event.getEntity().remove();
            return;
        }
        LiveBoss live = liveBySummon.get(summonId);
        if (live == null || !live.entityUuid().equals(event.getEntity().getUniqueId())) {
            return;
        }
        if (!terminalInFlight.add(summonId)) return;
        liveBySummon.remove(summonId, live);
        busyBossDefinitions.remove(live.bossDefinitionId(), summonId);
        boolean playerKill = event.getEntity().getKiller() != null;
        runAsync(() -> settleTerminal(live, playerKill));
    }

    private void prepareAndClaim(UUID minecraftUuid, UUID playerId, UUID contractId) {
        String bossDefinitionId = null;
        try {
            BountyContractSnapshot contract = bounties.loadContract(contractId);
            if (!contract.playerId().equals(playerId)) {
                throw new BountyException("That bounty contract belongs to another player.");
            }
            BountyTierDefinition tier = content.tiers().require(contract.familyId(), contract.tier());
            bossDefinitionId = tier.bossDefinitionId();
            PaperBountyBossPlacement placement = placements.require(bossDefinitionId);
            requirePlacementMatchesBootstrap(placement);
            if (busyBossDefinitions.putIfAbsent(bossDefinitionId, contractId) != null) {
                throw new BountyException("That bounty boss altar is currently occupied. Try again after the active boss resolves.");
            }

            var prepared = bounties.prepareSummon(
                    UUID.randomUUID(),
                    contractId,
                    playerId,
                    PREPARE_REASON
            );
            BountySummonLeaseResult claimed = bounties.claimSummon(
                    UUID.randomUUID(),
                    prepared.summon().summonId(),
                    backendId,
                    CLAIM_REASON
            );
            UUID summonId = claimed.summon().summonId();
            busyBossDefinitions.replace(bossDefinitionId, contractId, summonId);
            String finalBossDefinitionId = bossDefinitionId;
            runMain(() -> materializeOnMain(minecraftUuid, claimed.summon(), finalBossDefinitionId));
        } catch (SQLException | RuntimeException exception) {
            if (bossDefinitionId != null) busyBossDefinitions.remove(bossDefinitionId, contractId);
            handleFailure(minecraftUuid, "Could not summon bounty boss.", exception);
        }
    }

    private void materializeOnMain(
            UUID minecraftUuid,
            BountySummonSnapshot summon,
            String bossDefinitionId
    ) {
        PaperBountyBossPlacement placement = placements.require(bossDefinitionId);
        final World world = plugin.getServer().getWorld(placement.worldName());
        if (world == null) {
            busyBossDefinitions.remove(bossDefinitionId, summon.summonId());
            runAsync(() -> failAfterMaterializationFailure(summon.summonId(), minecraftUuid, "Boss world is unavailable."));
            return;
        }
        LivingEntity entity;
        try {
            Location location = new Location(
                    world,
                    placement.x(),
                    placement.y(),
                    placement.z(),
                    placement.yaw(),
                    placement.pitch()
            );
            entity = world.spawn(
                    location,
                    placement.entityClass(),
                    CreatureSpawnEvent.SpawnReason.CUSTOM,
                    spawned -> {
                        spawned.getPersistentDataContainer().set(
                                summonIdKey,
                                PersistentDataType.STRING,
                                summon.summonId().toString()
                        );
                        spawned.customName(Component.text(placement.displayName()));
                        spawned.setCustomNameVisible(true);
                        spawned.setPersistent(true);
                        spawned.setInvulnerable(true);
                    }
            );
        } catch (RuntimeException exception) {
            busyBossDefinitions.remove(bossDefinitionId, summon.summonId());
            runAsync(() -> failAfterMaterializationFailure(
                    summon.summonId(), minecraftUuid, "Could not materialize bounty boss."
            ));
            plugin.getLogger().log(Level.WARNING, "Bounty boss spawn failed", exception);
            return;
        }

        LiveBoss live = new LiveBoss(
                summon.summonId(),
                bossDefinitionId,
                entity.getUniqueId(),
                summon.stateVersion()
        );
        liveBySummon.put(summon.summonId(), live);
        runAsync(() -> bindMaterialization(minecraftUuid, live, placement));
    }

    private void bindMaterialization(
            UUID minecraftUuid,
            LiveBoss live,
            PaperBountyBossPlacement placement
    ) {
        try {
            materializations.record(
                    materializeOperationId(live.summonId()),
                    live.summonId(),
                    backendId,
                    live.bossDefinitionId(),
                    live.entityUuid(),
                    placement.worldName(),
                    placement.x(),
                    placement.y(),
                    placement.z()
            );
            runMain(() -> {
                LivingEntity entity = liveEntity(live.entityUuid());
                LiveBoss current = liveBySummon.get(live.summonId());
                if (entity == null || current == null || !current.entityUuid().equals(live.entityUuid())) return;
                entity.setInvulnerable(false);
                Player player = plugin.getServer().getPlayer(minecraftUuid);
                if (player != null && player.isOnline()) {
                    player.sendMessage(Component.text(
                            "Bounty boss " + placement.displayName() + " materialized at the authored altar."
                    ));
                }
            });
        } catch (SQLException | RuntimeException exception) {
            LiveBoss removed = liveBySummon.remove(live.summonId());
            busyBossDefinitions.remove(live.bossDefinitionId(), live.summonId());
            runMain(() -> {
                LivingEntity entity = liveEntity(live.entityUuid());
                if (entity != null) entity.remove();
            });
            plugin.getLogger().log(Level.WARNING, "Could not persist bounty boss materialization", exception);
            if (removed != null) {
                try {
                    failWithCurrentVersion(live.summonId());
                } catch (SQLException | RuntimeException failure) {
                    exception.addSuppressed(failure);
                    plugin.getLogger().log(Level.WARNING, "Could not fail unbound bounty boss attempt", failure);
                }
            }
            sendIfOnline(minecraftUuid, "Boss materialization failed. The summon attempt was consumed.");
        }
    }

    private void settleTerminal(LiveBoss live, boolean playerKill) {
        try {
            if (playerKill) {
                BountyCompletionResult result = completeWithCurrentVersion(live.summonId());
                notifyOwner(
                        result.contract().playerId(),
                        "Bounty boss defeated. Rewards were deposited into your "
                                + result.contract().familyId().value() + " bounty pouch."
                );
            } else {
                failWithCurrentVersion(live.summonId());
                notifyContractOwner(live.summonId(), "Bounty boss died without a player kill. The attempt failed.");
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not settle bounty boss terminal state", exception);
        } finally {
            terminalInFlight.remove(live.summonId());
        }
    }

    private BountyCompletionResult completeWithCurrentVersion(UUID summonId) throws SQLException {
        UUID operationId = terminalOperationId("complete", summonId);
        for (int attempt = 0; attempt < 4; attempt++) {
            BountySummonSnapshot current = bounties.loadSummon(summonId);
            if (current.status() == BountySummonStatus.DEFEATED) {
                throw new BountyException("Bounty summon was already defeated: " + summonId);
            }
            if (current.status() != BountySummonStatus.ACTIVE || !backendId.equals(current.ownerBackendId())) {
                throw new BountyException("Bounty summon is no longer ACTIVE on this backend: " + summonId);
            }
            try {
                return bounties.completeBoss(operationId, summonId, backendId, current.stateVersion(), COMPLETE_REASON);
            } catch (BountyException stale) {
                if (attempt == 3) throw stale;
            }
        }
        throw new BountyException("Could not settle bounty boss completion: " + summonId);
    }

    private void failWithCurrentVersion(UUID summonId) throws SQLException {
        UUID operationId = terminalOperationId("fail", summonId);
        for (int attempt = 0; attempt < 4; attempt++) {
            BountySummonSnapshot current = bounties.loadSummon(summonId);
            if (current.status() == BountySummonStatus.FAILED) return;
            if (current.status() != BountySummonStatus.ACTIVE || !backendId.equals(current.ownerBackendId())) {
                throw new BountyException("Bounty summon is no longer ACTIVE on this backend: " + summonId);
            }
            try {
                bounties.failBoss(operationId, summonId, backendId, current.stateVersion(), FAIL_REASON);
                return;
            } catch (BountyException stale) {
                if (attempt == 3) throw stale;
            }
        }
    }

    private void maintain() {
        heartbeatLiveBosses();
        recoverAbandonedSummons();
    }

    private void heartbeatLiveBosses() {
        for (LiveBoss live : List.copyOf(liveBySummon.values())) {
            if (terminalInFlight.contains(live.summonId())) continue;
            try {
                BountySummonSnapshot current = bounties.loadSummon(live.summonId());
                if (current.status() != BountySummonStatus.ACTIVE || !backendId.equals(current.ownerBackendId())) {
                    liveBySummon.remove(live.summonId(), live);
                    busyBossDefinitions.remove(live.bossDefinitionId(), live.summonId());
                    runMain(() -> {
                        LivingEntity entity = liveEntity(live.entityUuid());
                        if (entity != null) entity.remove();
                    });
                    continue;
                }
                BountySummonLeaseResult heartbeat = bounties.heartbeatSummon(
                        UUID.randomUUID(),
                        live.summonId(),
                        backendId,
                        current.stateVersion(),
                        HEARTBEAT_REASON
                );
                liveBySummon.computeIfPresent(live.summonId(), (ignored, existing) ->
                        existing.entityUuid().equals(live.entityUuid())
                                ? new LiveBoss(
                                        existing.summonId(),
                                        existing.bossDefinitionId(),
                                        existing.entityUuid(),
                                        heartbeat.summon().stateVersion()
                                )
                                : existing
                );
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.FINE, "Bounty boss lease heartbeat failed; lease expiry remains authoritative", exception);
            }
        }
    }

    private void recoverAbandonedSummons() {
        final List<UUID> candidates;
        try {
            candidates = recovery.listRecoverable(RECOVERY_READY_GRACE, RECOVERY_LIMIT);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not scan abandoned bounty summons", exception);
            return;
        }

        for (UUID summonId : candidates) {
            if (liveBySummon.containsKey(summonId) || terminalInFlight.contains(summonId)) continue;
            try {
                BountySummonSnapshot observed = bounties.loadSummon(summonId);
                BountyContractSnapshot contract = bounties.loadContract(observed.contractId());
                BountyTierDefinition tier = content.tiers().require(contract.familyId(), contract.tier());
                PaperBountyBossPlacement placement = placements.require(tier.bossDefinitionId());
                if (!placementMatchesBootstrap(placement)) continue;

                bounties.claimSummon(
                        UUID.randomUUID(),
                        summonId,
                        backendId,
                        RECOVERY_CLAIM_REASON
                );
                failWithCurrentVersion(summonId);
                busyBossDefinitions.remove(tier.bossDefinitionId(), summonId);
                notifyOwner(
                        contract.playerId(),
                        "An abandoned " + contract.familyId().value()
                                + " bounty boss attempt was recovered as failed. The summon was consumed."
                );
            } catch (BountyException exception) {
                // Another backend or a concurrent player action may have claimed/resolved it after the bounded scan.
                plugin.getLogger().log(Level.FINE, "Bounty summon recovery candidate changed concurrently: " + summonId, exception);
            } catch (SQLException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not recover abandoned bounty summon " + summonId, exception);
            }
        }
    }

    private void removeLoadedStaleBosses() {
        for (World world : plugin.getServer().getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (entity.getPersistentDataContainer().has(summonIdKey, PersistentDataType.STRING)) {
                    entity.remove();
                }
            }
        }
    }

    private void failAfterMaterializationFailure(UUID summonId, UUID minecraftUuid, String message) {
        try {
            failWithCurrentVersion(summonId);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not fail bounty summon after materialization failure", exception);
        }
        sendIfOnline(minecraftUuid, message + " The summon attempt was consumed.");
    }

    private void notifyContractOwner(UUID summonId, String message) {
        try {
            BountySummonSnapshot summon = bounties.loadSummon(summonId);
            BountyContractSnapshot contract = bounties.loadContract(summon.contractId());
            notifyOwner(contract.playerId(), message);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Could not resolve bounty contract owner for notification", exception);
        }
    }

    private void notifyOwner(UUID playerId, String message) {
        try {
            Optional<UUID> minecraftUuid = playerIdentities.resolveMinecraftUuid(playerId);
            minecraftUuid.ifPresent(value -> sendIfOnline(value, message));
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.FINE, "Could not resolve bounty owner Minecraft UUID", exception);
        }
    }

    private LivingEntity liveEntity(UUID entityUuid) {
        org.bukkit.entity.Entity entity = plugin.getServer().getEntity(entityUuid);
        return entity instanceof LivingEntity living ? living : null;
    }

    private void requirePlacementMatchesBootstrap(PaperBountyBossPlacement placement) {
        if (!placementMatchesBootstrap(placement)) {
            throw new BountyException(
                    "Bounty boss placement does not belong to this active zone/template: " + placement.bossDefinitionId()
            );
        }
    }

    private boolean placementMatchesBootstrap(PaperBountyBossPlacement placement) {
        return placement.zoneId().equals(bootstrapZone.zoneId())
                && placement.templateVersion().equals(bootstrapZone.templateVersion());
    }

    private void handleFailure(UUID minecraftUuid, String fallback, Throwable failure) {
        if (failure instanceof BountyException || failure instanceof IllegalArgumentException) {
            sendIfOnline(minecraftUuid, playerMessage(failure, fallback));
            return;
        }
        plugin.getLogger().log(Level.WARNING, fallback, failure);
        sendIfOnline(minecraftUuid, fallback);
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } catch (RejectedExecutionException | IllegalStateException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule bounty boss work", exception);
        }
    }

    private void runMain(Runnable task) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        runMain(() -> {
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline()) player.sendMessage(Component.text(message));
        });
    }

    private static UUID materializeOperationId(UUID summonId) {
        return UUID.nameUUIDFromBytes(
                ("paper-bounty-materialize:" + summonId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static UUID terminalOperationId(String kind, UUID summonId) {
        return UUID.nameUUIDFromBytes(
                ("paper-bounty-" + kind + ":" + summonId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String playerMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record LiveBoss(UUID summonId, String bossDefinitionId, UUID entityUuid, long stateVersion) {
        private LiveBoss {
            summonId = Objects.requireNonNull(summonId, "summonId");
            bossDefinitionId = requireText(bossDefinitionId, "bossDefinitionId");
            entityUuid = Objects.requireNonNull(entityUuid, "entityUuid");
            if (stateVersion < 0) throw new IllegalArgumentException("stateVersion must be >= 0");
        }
    }
}
