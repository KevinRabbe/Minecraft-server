package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable reward/classification entitlement created by consuming exactly one authorized source cycle. */
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
        if (sourceCycleNo < 0) {
            throw new IllegalArgumentException("sourceCycleNo must be >= 0");
        }
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            commodityDefinitionId = null;
            if (commodityQuantity != 0) {
                throw new IllegalArgumentException("commodityQuantity must be 0 without a commodity reward");
            }
        } else {
            commodityDefinitionId = commodityDefinitionId.trim();
            if (commodityQuantity <= 0) {
                throw new IllegalArgumentException("commodityQuantity must be > 0 with a commodity reward");
            }
        }
        if ((skillId == null && requestedExperience != 0)
                || (skillId != null && requestedExperience <= 0)) {
            throw new IllegalArgumentException("skill/xp entitlement shape is invalid");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean hasCommodityReward() {
        return commodityDefinitionId != null;
    }
}
