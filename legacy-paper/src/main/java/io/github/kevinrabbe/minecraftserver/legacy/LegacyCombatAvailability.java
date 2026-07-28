package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** Shared local predicate for whether an already-materialized execution may currently run combat. */
final class LegacyCombatAvailability {
    private LegacyCombatAvailability() { }

    static boolean isEnabled(
            LegacyExecution execution,
            LegacyCompetitiveCombatGate combatGate,
            Predicate<UUID> isOnline
    ) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(combatGate, "combatGate");
        Objects.requireNonNull(isOnline, "isOnline");

        if (!combatGate.isEnabled(execution.getExecutionId())) {
            return false;
        }
        if (LegacyRankedExecution.ACTIVITY_KIND.equals(execution.getActivityKind())) {
            for (LegacyParticipant participant : execution.getParticipants()) {
                if (!isOnline.test(participant.getMinecraftUuid())) {
                    return false;
                }
            }
            return true;
        }
        if (LegacyClanWarExecution.ACTIVITY_KIND.equals(execution.getActivityKind())) {
            return true;
        }
        return false;
    }
}
