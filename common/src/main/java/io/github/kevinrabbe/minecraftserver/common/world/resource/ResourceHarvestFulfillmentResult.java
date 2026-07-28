package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillXpAwardResult;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable fulfillment result for one immutable resource harvest entitlement. */
public record ResourceHarvestFulfillmentResult(
        ResourceHarvestEntitlement entitlement,
        UUID commodityDeliveryId,
        SkillXpAwardResult experienceAward,
        Instant completedAt
) {
    public ResourceHarvestFulfillmentResult {
        entitlement = Objects.requireNonNull(entitlement, "entitlement");
        commodityDeliveryId = Objects.requireNonNull(commodityDeliveryId, "commodityDeliveryId");
        if ((entitlement.skillId() == null) != (experienceAward == null)) {
            throw new IllegalArgumentException("experience award shape does not match entitlement");
        }
        if (experienceAward != null
                && (!experienceAward.playerId().equals(entitlement.playerId())
                || !experienceAward.skillId().equals(entitlement.skillId())
                || experienceAward.requestedExperience() != entitlement.requestedExperience())) {
            throw new IllegalArgumentException("experience award does not match harvest entitlement");
        }
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }
}
