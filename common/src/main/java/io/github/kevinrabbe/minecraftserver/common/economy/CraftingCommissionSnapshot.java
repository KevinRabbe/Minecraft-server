package io.github.kevinrabbe.minecraftserver.common.economy;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Authoritative funded crafting commission snapshot. */
public record CraftingCommissionSnapshot(
        UUID commissionId,
        UUID requesterPlayerId,
        UUID workerPlayerId,
        String recipeId,
        int recipeVersion,
        CraftingCommissionStatus status,
        Map<String, Long> materialQuantities,
        long paymentMinor,
        long stateVersion,
        Instant createdAt,
        Instant acceptedAt,
        Instant settledAt
) {
    public CraftingCommissionSnapshot {
        commissionId = Objects.requireNonNull(commissionId, "commissionId");
        requesterPlayerId = Objects.requireNonNull(requesterPlayerId, "requesterPlayerId");
        if (recipeId == null || recipeId.isBlank()) {
            throw new IllegalArgumentException("recipeId must not be blank");
        }
        status = Objects.requireNonNull(status, "status");
        materialQuantities = Map.copyOf(Objects.requireNonNull(materialQuantities, "materialQuantities"));
        if (materialQuantities.isEmpty()) {
            throw new IllegalArgumentException("commission must have material escrow");
        }
        if (recipeVersion < 0 || paymentMinor < 0 || stateVersion < 0) {
            throw new IllegalArgumentException("commission numeric values must be nonnegative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if ((status == CraftingCommissionStatus.ACCEPTED || status == CraftingCommissionStatus.COMPLETED)
                && (workerPlayerId == null || workerPlayerId.equals(requesterPlayerId) || acceptedAt == null)) {
            throw new IllegalArgumentException("accepted/completed commission requires a distinct worker and timestamp");
        }
        if ((status == CraftingCommissionStatus.COMPLETED || status == CraftingCommissionStatus.CANCELLED)
                != (settledAt != null)) {
            throw new IllegalArgumentException("terminal commission status/timestamp shape is invalid");
        }
    }
}
