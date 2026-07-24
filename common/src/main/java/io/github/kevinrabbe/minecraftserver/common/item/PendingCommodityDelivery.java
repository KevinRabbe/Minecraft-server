package io.github.kevinrabbe.minecraftserver.common.item;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PendingCommodityDelivery(
        UUID deliveryId,
        UUID recipientPlayerId,
        String definitionId,
        long totalQuantity,
        long remainingQuantity,
        PendingCommodityStatus status,
        UUID issueOperationId,
        String issueReason,
        UUID lastClaimOperationId,
        Instant createdAt,
        Instant claimedAt
) {
    public PendingCommodityDelivery {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        recipientPlayerId = Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (totalQuantity <= 0 || remainingQuantity < 0 || remainingQuantity > totalQuantity) {
            throw new IllegalArgumentException("invalid commodity delivery quantities");
        }
        status = Objects.requireNonNull(status, "status");
        issueOperationId = Objects.requireNonNull(issueOperationId, "issueOperationId");
        if (issueReason == null || issueReason.isBlank()) {
            throw new IllegalArgumentException("issueReason must not be blank");
        }
        issueReason = issueReason.trim();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");

        if (status == PendingCommodityStatus.PENDING && (remainingQuantity == 0 || claimedAt != null)) {
            throw new IllegalArgumentException("PENDING commodity delivery must retain quantity and no claimedAt");
        }
        if (status == PendingCommodityStatus.CLAIMED && (remainingQuantity != 0 || claimedAt == null)) {
            throw new IllegalArgumentException("CLAIMED commodity delivery must be empty and have claimedAt");
        }
    }
}
