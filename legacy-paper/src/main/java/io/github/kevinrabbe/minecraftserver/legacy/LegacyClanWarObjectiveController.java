package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Main-thread controller for the first control-point objective. Dormant until a Clan-War combat gate is opened. */
final class LegacyClanWarObjectiveController {
    private final LegacyCompetitivePlugin plugin;
    private final LegacyCompetitiveCombatGate combatGate;
    private final World world;
    private final LegacyClanWarControlPointGeometry geometry;
    private final Map<UUID, LegacyClanWarObjective> objectivesByExecution =
            new HashMap<UUID, LegacyClanWarObjective>();
    private LegacyClanWarTimeoutTracker timeoutTracker;

    LegacyClanWarObjectiveController(
            LegacyCompetitivePlugin plugin,
            LegacyCompetitiveCombatGate combatGate,
            World world,
            LegacyClanWarControlPointGeometry geometry
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combatGate = Objects.requireNonNull(combatGate, "combatGate");
        this.world = Objects.requireNonNull(world, "world");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
    }

    void tick() {
        Map<UUID, LegacyClanWarRuntimeState> runtimeStates = plugin.snapshotClanWarRuntimeStates();
        objectivesByExecution.keySet().retainAll(runtimeStates.keySet());

        LegacyClanWarTimeoutTracker existingTimeoutTracker = timeoutTracker;
        if (existingTimeoutTracker != null) {
            existingTimeoutTracker.retain(runtimeStates.keySet());
        }

        for (Map.Entry<UUID, LegacyClanWarRuntimeState> entry : runtimeStates.entrySet()) {
            UUID executionId = entry.getKey();
            if (!combatGate.isEnabled(executionId)) continue;

            LegacyClanWarRuntimeState runtimeState = entry.getValue();
            LegacyClanWarTimeoutTracker tracker = timeoutTracker;
            if (tracker == null) {
                tracker = new LegacyClanWarTimeoutTracker(
                        runtimeState.getObjectiveSettings().getMatchTimeoutSeconds()
                );
                timeoutTracker = tracker;
            }
            tracker.start(executionId);
            if (tracker.isExpired(executionId)) {
                try {
                    plugin.recordFailure(executionId);
                } catch (IllegalStateException ignored) {
                    // Another terminal path already removed this execution locally.
                } finally {
                    tracker.clear(executionId);
                    objectivesByExecution.remove(executionId);
                }
                continue;
            }

            LegacyClanWarObjective objective = objectivesByExecution.get(executionId);
            if (objective == null) {
                objective = new LegacyClanWarObjective(
                        runtimeState.getWar(),
                        runtimeState.getObjectiveSettings()
                );
                objectivesByExecution.put(executionId, objective);
            }

            LegacyClanWarControlPointPresence.Counts counts = LegacyClanWarControlPointPresence.count(
                    runtimeState.getWar(),
                    this::isInside
            );
            UUID winnerSideId = objective.evaluate(
                    counts.getChallenger(),
                    counts.getDefender()
            );
            if (winnerSideId == null) continue;

            try {
                plugin.recordWinnerSide(executionId, winnerSideId);
            } catch (IllegalStateException ignored) {
                // Another terminal path already removed this execution locally.
            } finally {
                tracker.clear(executionId);
                objectivesByExecution.remove(executionId);
            }
        }
    }

    private boolean isInside(UUID minecraftUuid) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null
                || !player.isOnline()
                || player.isDead()
                || player.getGameMode() == GameMode.SPECTATOR
                || !world.equals(player.getWorld())) {
            return false;
        }
        Location location = player.getLocation();
        return geometry.contains(location.getX(), location.getY(), location.getZ());
    }
}
