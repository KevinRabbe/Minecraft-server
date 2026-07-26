package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral local permission to execute combat for a fully materialized competitive execution.
 *
 * <p>The gate is intentionally closed by default. Routing/admission alone never enables combat. A future arena/loadout
 * materializer must explicitly enable one execution only after its temporary state is ready, and must disable it again
 * on teardown. No persistent value or rating authority lives here.</p>
 */
final class LegacyCompetitiveCombatGate {
    private final Set<UUID> enabledExecutions = ConcurrentHashMap.newKeySet();

    boolean isEnabled(UUID executionId) {
        return enabledExecutions.contains(Objects.requireNonNull(executionId, "executionId"));
    }

    void enable(UUID executionId) {
        enabledExecutions.add(Objects.requireNonNull(executionId, "executionId"));
    }

    void disable(UUID executionId) {
        enabledExecutions.remove(Objects.requireNonNull(executionId, "executionId"));
    }

    void clear() {
        enabledExecutions.clear();
    }
}
