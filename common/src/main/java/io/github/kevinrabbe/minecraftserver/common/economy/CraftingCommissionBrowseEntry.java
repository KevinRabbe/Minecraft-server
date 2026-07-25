package io.github.kevinrabbe.minecraftserver.common.economy;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Complete immutable economic terms for one currently OPEN crafting commission. */
public record CraftingCommissionBrowseEntry(
        UUID commissionId,
        UUID requesterPlayerId,
        String recipeId,
        int recipeVersion,
        Map<String, Long> materialQuantities,
        long paymentMinor,
        Instant createdAt
) {
    public CraftingCommissionBrowseEntry {
        commissionId = Objects.requireNonNull(commissionId, "commissionId");
        requesterPlayerId = Objects.requireNonNull(requesterPlayerId, "requesterPlayerId");
        if (recipeId == null || recipeId.isBlank()) {
            throw new IllegalArgumentException("recipeId must not be blank");
        }
        recipeId = recipeId.trim();
        if (recipeVersion < 0 || paymentMinor < 0) {
            throw new IllegalArgumentException("recipeVersion/paymentMinor must be nonnegative");
        }
        Objects.requireNonNull(materialQuantities, "materialQuantities");
        if (materialQuantities.isEmpty()) {
            throw new IllegalArgumentException("OPEN commission browse entry must include full material escrow");
        }
        TreeMap<String, Long> normalized = new TreeMap<>();
        materialQuantities.forEach((definitionId, quantity) -> {
            if (definitionId == null || definitionId.isBlank() || quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("invalid commission material term");
            }
            normalized.put(definitionId.trim(), quantity);
        });
        materialQuantities = Map.copyOf(normalized);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
