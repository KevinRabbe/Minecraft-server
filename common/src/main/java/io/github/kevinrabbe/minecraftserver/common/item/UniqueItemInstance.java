package io.github.kevinrabbe.minecraftserver.common.item;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative persistent identity/location snapshot for one individual item. */
public record UniqueItemInstance(
        UUID itemInstanceId,
        String definitionId,
        ItemLocation location,
        long stateVersion,
        UUID originalOwnerPlayerId,
        UUID createdByOperationId,
        String createdReason,
        Instant createdAt,
        Instant updatedAt
) {
    public UniqueItemInstance {
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        location = Objects.requireNonNull(location, "location");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        originalOwnerPlayerId = Objects.requireNonNull(originalOwnerPlayerId, "originalOwnerPlayerId");
        createdByOperationId = Objects.requireNonNull(createdByOperationId, "createdByOperationId");
        if (createdReason == null || createdReason.isBlank()) {
            throw new IllegalArgumentException("createdReason must not be blank");
        }
        createdReason = createdReason.trim();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
