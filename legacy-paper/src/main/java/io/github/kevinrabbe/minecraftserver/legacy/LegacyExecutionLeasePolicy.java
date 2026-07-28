package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Local liveness policy: a runtime extends only executions it can currently execute as combat. */
final class LegacyExecutionLeasePolicy {
    private LegacyExecutionLeasePolicy() { }

    static boolean shouldRenew(
            LegacyExecution execution,
            LegacyCompetitiveCombatGate combatGate,
            Set<UUID> onlineMinecraftUuids
    ) {
        Objects.requireNonNull(onlineMinecraftUuids, "onlineMinecraftUuids");
        return LegacyCombatAvailability.isEnabled(execution, combatGate, onlineMinecraftUuids::contains);
    }
}
