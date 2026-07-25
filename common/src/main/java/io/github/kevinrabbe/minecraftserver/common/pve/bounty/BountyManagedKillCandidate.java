package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative entity harvest not yet classified for configured bounty progress. */
public record BountyManagedKillCandidate(
        UUID resourceKillOperationId,
        UUID playerId,
        String sourceDefinitionId,
        Instant harvestedAt
) {
    public BountyManagedKillCandidate {
        resourceKillOperationId = Objects.requireNonNull(resourceKillOperationId, "resourceKillOperationId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (sourceDefinitionId == null || sourceDefinitionId.isBlank()) {
            throw new IllegalArgumentException("sourceDefinitionId must not be blank");
        }
        sourceDefinitionId = sourceDefinitionId.trim();
        harvestedAt = Objects.requireNonNull(harvestedAt, "harvestedAt");
    }
}
