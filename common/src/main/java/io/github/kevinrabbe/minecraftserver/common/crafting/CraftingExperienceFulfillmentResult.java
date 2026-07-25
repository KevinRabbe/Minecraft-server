package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillXpAwardResult;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CraftingExperienceFulfillmentResult(
        UUID craftId,
        UUID xpOperationId,
        SkillXpAwardResult experienceAward,
        Instant completedAt
) {
    public CraftingExperienceFulfillmentResult {
        craftId = Objects.requireNonNull(craftId, "craftId");
        xpOperationId = Objects.requireNonNull(xpOperationId, "xpOperationId");
        experienceAward = Objects.requireNonNull(experienceAward, "experienceAward");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }
}
