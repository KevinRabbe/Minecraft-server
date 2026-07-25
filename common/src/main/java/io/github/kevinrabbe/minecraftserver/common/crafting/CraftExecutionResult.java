package io.github.kevinrabbe.minecraftserver.common.crafting;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable result of one exactly-once craft operation. */
public record CraftExecutionResult(
        UUID craftId,
        UUID operationId,
        UUID crafterPlayerId,
        String recipeId,
        int recipeVersion,
        String outputDefinitionId,
        long outputQuantity,
        UUID deliveryId,
        UUID itemInstanceId,
        Map<String, Integer> rollQualityBasisPoints,
        Instant createdAt
) {
    public CraftExecutionResult {
        craftId = Objects.requireNonNull(craftId, "craftId");
        operationId = Objects.requireNonNull(operationId, "operationId");
        crafterPlayerId = Objects.requireNonNull(crafterPlayerId, "crafterPlayerId");
        if (recipeId == null || recipeId.isBlank() || outputDefinitionId == null || outputDefinitionId.isBlank()) {
            throw new IllegalArgumentException("recipe/output definition ids must not be blank");
        }
        if (recipeVersion < 0 || outputQuantity <= 0) {
            throw new IllegalArgumentException("invalid recipe version/output quantity");
        }
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        rollQualityBasisPoints = Map.copyOf(Objects.requireNonNull(rollQualityBasisPoints, "rollQualityBasisPoints"));
        rollQualityBasisPoints.forEach((property, quality) -> {
            if (property == null || property.isBlank() || quality == null || quality < 0 || quality > 10_000) {
                throw new IllegalArgumentException("invalid intrinsic roll result");
            }
        });
        if (itemInstanceId == null && !rollQualityBasisPoints.isEmpty()) {
            throw new IllegalArgumentException("commodity output cannot carry intrinsic roll state");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
