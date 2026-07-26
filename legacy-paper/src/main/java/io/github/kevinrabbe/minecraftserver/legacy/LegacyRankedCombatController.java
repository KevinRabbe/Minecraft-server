package io.github.kevinrabbe.minecraftserver.legacy;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Objects;

/** Converts one live Ranked death into the already-existing exactly-once winner report boundary. */
final class LegacyRankedCombatController implements Listener {
    private final LegacyCompetitivePlugin plugin;
    private final LegacyCompetitiveCombatGate combatGate;

    LegacyRankedCombatController(LegacyCompetitivePlugin plugin, LegacyCompetitiveCombatGate combatGate) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combatGate = Objects.requireNonNull(combatGate, "combatGate");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        LegacyExecution execution = plugin.findExecutionForPlayer(victim.getUniqueId());
        if (execution == null
                || !LegacyRankedExecution.ACTIVITY_KIND.equals(execution.getActivityKind())
                || !combatGate.isEnabled(execution.getExecutionId())) {
            return;
        }

        LegacyRankedExecution ranked = LegacyRankedExecution.requireSupported(execution);
        LegacyParticipant winner = ranked.opponent(victim.getUniqueId());
        try {
            plugin.recordWinner(execution.getExecutionId(), winner.getMinecraftUuid());
        } catch (IllegalStateException ignored) {
            // A second death event after the first terminal report is harmless; the first outcome already closed locally.
        }
    }
}
