package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Local liveness policy: a runtime extends only executions it has actually materialized enough to run. */
final class LegacyExecutionLeasePolicy {
    private LegacyExecutionLeasePolicy() { }

    static boolean shouldRenew(
            LegacyExecution execution,
            LegacyCompetitiveCombatGate combatGate,
            Set<UUID> onlineMinecraftUuids
    ) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(combatGate, "combatGate");
        Objects.requireNonNull(onlineMinecraftUuids, "onlineMinecraftUuids");

        if (!combatGate.isEnabled(execution.getExecutionId())) {
            return false;
        }
        if (LegacyRankedExecution.ACTIVITY_KIND.equals(execution.getActivityKind())) {
            return execution.allParticipantsOnline(onlineMinecraftUuids);
        }
        if (LegacyClanWarExecution.ACTIVITY_KIND.equals(execution.getActivityKind())) {
            return true;
        }
        return false;
    }
}
