package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;
import java.util.UUID;

public record PendingCommodityClaimResult(
        UUID deliveryId,
        UUID recipientPlayerId,
        String definitionId,
        long claimedQuantity,
        long remainingQuantity,
        long playerStateVersion,
        PendingCommodityStatus status
) {
    public PendingCommodityClaimResult {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        recipientPlayerId = Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (claimedQuantity <= 0 || remainingQuantity < 0 || playerStateVersion < 0) {
            throw new IllegalArgumentException("invalid claim quantity/version");
        }
        status = Objects.requireNonNull(status, "status");
        if ((status == PendingCommodityStatus.PENDING && remainingQuantity == 0)
                || (status == PendingCommodityStatus.CLAIMED && remainingQuantity != 0)) {
            throw new IllegalArgumentException("status does not match remainingQuantity");
        }
    }
}
