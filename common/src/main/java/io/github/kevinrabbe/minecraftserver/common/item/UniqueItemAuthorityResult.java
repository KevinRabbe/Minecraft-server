package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;
import java.util.UUID;

/** Exact result persisted under one operation_id so retries reproduce the committed mutation result. */
public record UniqueItemAuthorityResult(
        UUID itemInstanceId,
        String definitionId,
        long stateVersion,
        ItemLocation location
) {
    public UniqueItemAuthorityResult {
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        location = Objects.requireNonNull(location, "location");
    }
}
