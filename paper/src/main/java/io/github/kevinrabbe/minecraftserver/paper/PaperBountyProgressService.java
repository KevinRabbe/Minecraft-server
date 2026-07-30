package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContentCatalog;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyContractStatus;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyKillProgressRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyKillProgressResult;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyManagedKillCandidate;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Durable adapter from authoritative ordinary-PvE entity harvests to configured bounty-family kill progress. */
final class PaperBountyProgressService {
    private static final String PROGRESS_REASON = "bounty.managed_kill";
    private static final int RECOVERY_BATCH = 100;
    private static final long RECOVERY_PERIOD_TICKS = 100L;

    private final JavaPlugin plugin;
    private final PaperPlayerIdentityResolver playerIdentities;
    private final BountyContentCatalog content;
    private final BountyKillProgressRepository progress;
    private final AtomicBoolean recoveryInFlight = new AtomicBoolean();

    private BukkitTask recoveryTask;

    PaperBountyProgressService(
            JavaPlugin plugin,
            PaperPlayerIdentityResolver playerIdentities,
            BountyContentCatalog content,
            BountyKillProgressRepository progress
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerIdentities = Objects.requireNonNull(playerIdentities, "playerIdentities");
        this.content = Objects.requireNonNull(content, "content");
        this.progress = Objects.requireNonNull(progress, "progress").withContent(this.content);
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

    void recordSettledKill(UUID playerId, String sourceDefinitionId, UUID resourceKillOperationId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(resourceKillOperationId, "resourceKillOperationId");
        Optional<io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyFamilyId> family =
                content.eligibleFamilyForSource(sourceDefinitionId);
        if (family.isEmpty()) return;

        BountyKillProgressResult result = progress.recordManagedKill(
                resourceKillOperationId,
                playerId,
                sourceDefinitionId,
                family.orElseThrow(),
                1,
                PROGRESS_REASON
        );
        notifyProgress(result);
    }

    private void recoverPending() {
        if (!plugin.isEnabled() || !recoveryInFlight.compareAndSet(false, true)) return;
        try {
            List<BountyManagedKillCandidate> pending = progress.listUnclassifiedManagedKills(
                    content.eligibleSourceDefinitionIds(),
                    RECOVERY_BATCH
            );
            for (BountyManagedKillCandidate candidate : pending) {
                try {
                    recordSettledKill(
                            candidate.playerId(),
                            candidate.sourceDefinitionId(),
                            candidate.resourceKillOperationId()
                    );
                } catch (SQLException | RuntimeException exception) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Could not recover bounty progress for managed kill " + candidate.resourceKillOperationId(),
                            exception
                    );
                }
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not scan recoverable managed bounty kills", exception);
        } finally {
            recoveryInFlight.set(false);
        }
    }

    private void notifyProgress(BountyKillProgressResult result) {
        if (!result.applied()) return;
        try {
            Optional<UUID> minecraftUuid = playerIdentities.resolveMinecraftUuid(result.playerId());
            if (minecraftUuid.isEmpty()) return;
            UUID target = minecraftUuid.orElseThrow();
            var contract = result.contract();
            String message = contract.status() == BountyContractStatus.SUMMON_READY
                    ? "Bounty " + contract.familyId().value() + " T" + contract.tier()
                            + " hunt complete. Boss summon is ready."
                    : "Bounty " + contract.familyId().value() + " T" + contract.tier() + ": "
                            + contract.eligibleKillProgress() + "/" + contract.requiredEligibleKills() + " kills.";
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(target);
                if (player != null && player.isOnline()) player.sendMessage(Component.text(message));
            });
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.FINE, "Could not resolve online player for bounty progress message", exception);
        }
    }
}
