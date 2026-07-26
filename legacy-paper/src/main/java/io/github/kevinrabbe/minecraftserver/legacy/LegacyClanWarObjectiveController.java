package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Main-thread controller for the first control-point objective. Dormant until a Clan-War combat gate is opened. */
final class LegacyClanWarObjectiveController {
    private final LegacyCompetitivePlugin plugin;
    private final LegacyCompetitiveCombatGate combatGate;
    private final World world;
    private final LegacyClanWarControlPointGeometry geometry;

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
        for (Map.Entry<UUID, LegacyClanWarRuntimeState> entry : plugin.snapshotClanWarRuntimeStates().entrySet()) {
            UUID executionId = entry.getKey();
            if (!combatGate.isEnabled(executionId)) continue;

            LegacyClanWarRuntimeState runtimeState = entry.getValue();
            LegacyClanWarControlPointPresence.Counts counts = LegacyClanWarControlPointPresence.count(
                    runtimeState.getWar(),
                    this::isInside
            );
            UUID winnerSideId = runtimeState.getObjective().evaluate(
                    counts.getChallenger(),
                    counts.getDefender()
            );
            if (winnerSideId == null) continue;

            try {
                plugin.recordWinnerSide(executionId, winnerSideId);
            } catch (IllegalStateException ignored) {
                // Another terminal path already removed this execution locally.
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
