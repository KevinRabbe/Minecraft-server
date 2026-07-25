package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable client request for one funded crafting commission. */
public record CraftingCommissionRequest(
        String recipeId,
        int recipeVersion,
        Map<String, Long> materialQuantities,
        long paymentMinor
) {
    public CraftingCommissionRequest {
        if (recipeId == null || recipeId.isBlank()) {
            throw new IllegalArgumentException("recipeId must not be blank");
        }
        recipeId = recipeId.trim();
        if (recipeVersion < 0) {
            throw new IllegalArgumentException("recipeVersion must be >= 0");
        }
        Objects.requireNonNull(materialQuantities, "materialQuantities");
        if (materialQuantities.isEmpty() || materialQuantities.size() > 64) {
            throw new IllegalArgumentException("materialQuantities must contain between 1 and 64 entries");
        }
        TreeMap<String, Long> normalized = new TreeMap<>();
        materialQuantities.forEach((definitionId, quantity) -> {
            if (definitionId == null || definitionId.isBlank()) {
                throw new IllegalArgumentException("material definitionId must not be blank");
            }
            if (quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("material quantity must be > 0");
            }
            normalized.put(definitionId.trim(), quantity);
        });
        materialQuantities = Map.copyOf(normalized);
        if (paymentMinor < 0) {
            throw new IllegalArgumentException("paymentMinor must be >= 0");
        }
    }
}
