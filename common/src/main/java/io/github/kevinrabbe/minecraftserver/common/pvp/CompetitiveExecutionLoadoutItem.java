package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.Objects;
import java.util.UUID;

/** Immutable execution-scoped combat item projection with no persistent unique-item identity. */
public record CompetitiveExecutionLoadoutItem(
        UUID executionId,
        int participantIndex,
        int loadoutItemIndex,
        String definitionId,
        String rollStateJson,
        int upgradeLevel
) {
    public CompetitiveExecutionLoadoutItem {
        executionId = Objects.requireNonNull(executionId, "executionId");
        if (participantIndex < 0 || loadoutItemIndex < 0) {
            throw new IllegalArgumentException("participant/loadout item indexes must be >= 0");
        }
        definitionId = requireText(definitionId, "definitionId");
        rollStateJson = requireText(rollStateJson, "rollStateJson");
        if (upgradeLevel < 0) throw new IllegalArgumentException("upgradeLevel must be >= 0");
    }

    private static String requireText(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
