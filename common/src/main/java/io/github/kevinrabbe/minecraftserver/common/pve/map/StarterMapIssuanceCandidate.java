package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative managed entity harvest that still needs its one starter Map issuance classified. */
public record StarterMapIssuanceCandidate(
        UUID resourceKillOperationId,
        UUID playerId,
        String sourceDefinitionId,
        String worldEraId,
        Instant killedAt
) {
    public StarterMapIssuanceCandidate {
        resourceKillOperationId = Objects.requireNonNull(resourceKillOperationId, "resourceKillOperationId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (sourceDefinitionId == null || sourceDefinitionId.isBlank()) {
            throw new IllegalArgumentException("sourceDefinitionId must not be blank");
        }
        sourceDefinitionId = sourceDefinitionId.trim();
        if (worldEraId == null || worldEraId.isBlank()) {
            throw new IllegalArgumentException("worldEraId must not be blank");
        }
        worldEraId = worldEraId.trim();
        killedAt = Objects.requireNonNull(killedAt, "killedAt");
    }
}
