package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral local permission to execute combat for a fully materialized competitive execution.
 *
 * <p>The gate is intentionally closed by default. Routing/admission alone never enables combat. A materializer must
 * explicitly enable an execution only after its temporary state is ready, and must disable it again on teardown. No
 * persistent value or rating authority lives here.</p>
 */
final class LegacyCompetitiveCombatGate {
    private final Set<UUID> enabledExecutions = ConcurrentHashMap.newKeySet();

    boolean isEnabled(UUID executionId) {
        return enabledExecutions.contains(Objects.requireNonNull(executionId, "executionId"));
    }

    void enable(UUID executionId) {
        enabledExecutions.add(Objects.requireNonNull(executionId, "executionId"));
    }

    /**
     * Reserves the current single-arena V1 runtime for exactly one execution. This does not define a permanent backend
     * capacity rule; a future multi-arena materializer may use independent arena slots and the ordinary enable method.
     */
    synchronized boolean enableExclusive(UUID executionId) {
        Objects.requireNonNull(executionId, "executionId");
        if (enabledExecutions.isEmpty()) {
            enabledExecutions.add(executionId);
            return true;
        }
        return enabledExecutions.size() == 1 && enabledExecutions.contains(executionId);
    }

    void disable(UUID executionId) {
        enabledExecutions.remove(Objects.requireNonNull(executionId, "executionId"));
    }

    /** Drops any local combat permission whose execution is no longer in the trusted active snapshot. */
    synchronized void retain(Set<UUID> activeExecutionIds) {
        Objects.requireNonNull(activeExecutionIds, "activeExecutionIds");
        enabledExecutions.retainAll(activeExecutionIds);
    }

    void clear() {
        enabledExecutions.clear();
    }
}
