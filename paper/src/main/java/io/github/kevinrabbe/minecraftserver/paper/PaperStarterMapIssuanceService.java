package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPendingDeliveryAuthority;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPendingDeliveryResult;
import io.github.kevinrabbe.minecraftserver.common.pve.map.StarterMapIssuanceCandidate;
import io.github.kevinrabbe.minecraftserver.common.pve.map.StarterMapIssuanceRepository;
import io.github.kevinrabbe.minecraftserver.common.world.WorldProgressionQueryRepository;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Recoverable bridge from the authored starter elite's managed kill to one individualized first Map. */
final class PaperStarterMapIssuanceService {
    private static final String ISSUE_REASON = "map.starter_elite";
    private static final int RECOVERY_BATCH = 100;
    private static final long RECOVERY_PERIOD_TICKS = 100L;

    private final JavaPlugin plugin;
    private final PaperStarterMapPolicy policy;
    private final StarterMapIssuanceRepository issuances;
    private final MapPendingDeliveryAuthority pendingMaps;
    private final WorldProgressionQueryRepository worldProgression;
    private final AtomicBoolean recoveryInFlight = new AtomicBoolean();

    private BukkitTask recoveryTask;

    PaperStarterMapIssuanceService(
            JavaPlugin plugin,
            PaperStarterMapPolicy policy,
            StarterMapIssuanceRepository issuances,
            MapPendingDeliveryAuthority pendingMaps,
            WorldProgressionQueryRepository worldProgression
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.issuances = Objects.requireNonNull(issuances, "issuances");
        this.pendingMaps = Objects.requireNonNull(pendingMaps, "pendingMaps");
        this.worldProgression = Objects.requireNonNull(worldProgression, "worldProgression");
    }

    void start() {
        if (recoveryTask != null || !plugin.isEnabled()) return;
        recoveryTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::recoverPending,
                1L,
                RECOVERY_PERIOD_TICKS
        );
    }

    void stop() {
        if (recoveryTask != null) {
            recoveryTask.cancel();
            recoveryTask = null;
        }
    }

    private void recoverPending() {
        if (!plugin.isEnabled() || !recoveryInFlight.compareAndSet(false, true)) return;
        try {
            List<StarterMapIssuanceCandidate> pending = issuances.listUnissued(
                    policy.sourceDefinitionId(),
                    RECOVERY_BATCH
            );
            if (pending.isEmpty()) return;

            Optional<io.github.kevinrabbe.minecraftserver.common.world.WorldEraSnapshot> currentEra =
                    worldProgression.currentEra();
            if (currentEra.isEmpty()) {
                plugin.getLogger().warning("Starter Map issuance is waiting for a current world era");
                return;
            }
            String worldEraId = currentEra.orElseThrow().eraId().value();
            for (StarterMapIssuanceCandidate candidate : pending) {
                try {
                    issue(candidate, worldEraId);
                } catch (SQLException | RuntimeException exception) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Could not recover starter Map issuance for managed kill "
                                    + candidate.resourceKillOperationId(),
                            exception
                    );
                }
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not scan recoverable starter Map kills", exception);
        } finally {
            recoveryInFlight.set(false);
        }
    }

    private void issue(StarterMapIssuanceCandidate candidate, String worldEraId) throws SQLException {
        UUID issueOperationId = issueOperationId(candidate.resourceKillOperationId());
        MapPendingDeliveryResult pending = pendingMaps.createPending(
                issueOperationId,
                policy.mapDefinitionId(),
                candidate.playerId(),
                policy.runDefinition(worldEraId, generationSeed(candidate.resourceKillOperationId())),
                ISSUE_REASON
        );
        issuances.recordIssued(
                candidate.resourceKillOperationId(),
                candidate.sourceDefinitionId(),
                issueOperationId,
                candidate.playerId(),
                pending
        );
    }

    static UUID issueOperationId(UUID resourceKillOperationId) {
        Objects.requireNonNull(resourceKillOperationId, "resourceKillOperationId");
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:starter-map:" + resourceKillOperationId)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    static long generationSeed(UUID resourceKillOperationId) {
        Objects.requireNonNull(resourceKillOperationId, "resourceKillOperationId");
        return resourceKillOperationId.getMostSignificantBits() ^ resourceKillOperationId.getLeastSignificantBits();
    }
}
