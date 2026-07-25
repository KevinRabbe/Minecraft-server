package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable reward entitlement created by consuming exactly one authorized source cycle. */
public record ResourceHarvestEntitlement(
        UUID harvestId,
        UUID operationId,
        UUID sourceId,
        long sourceCycleNo,
        UUID playerId,
        String commodityDefinitionId,
        long commodityQuantity,
        SkillId skillId,
        long requestedExperience,
        Instant createdAt
) {
    public ResourceHarvestEntitlement {
        harvestId = Objects.requireNonNull(harvestId, "harvestId");
        operationId = Objects.requireNonNull(operationId, "operationId");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (sourceCycleNo < 0 || commodityQuantity <= 0) {
            throw new IllegalArgumentException("invalid source cycle/commodity quantity");
        }
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        if ((skillId == null && requestedExperience != 0)
                || (skillId != null && requestedExperience <= 0)) {
            throw new IllegalArgumentException("skill/xp entitlement shape is invalid");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
