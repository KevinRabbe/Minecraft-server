package io.github.kevinrabbe.minecraftserver.common.item;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PendingUniqueDelivery(
        UUID deliveryId,
        UUID recipientPlayerId,
        UUID itemInstanceId,
        PendingDeliveryStatus status,
        UUID issueOperationId,
        UUID claimOperationId,
        String issueReason,
        Instant createdAt,
        Instant claimedAt
) {
    public PendingUniqueDelivery {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        recipientPlayerId = Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        status = Objects.requireNonNull(status, "status");
        issueOperationId = Objects.requireNonNull(issueOperationId, "issueOperationId");
        if (issueReason == null || issueReason.isBlank()) {
            throw new IllegalArgumentException("issueReason must not be blank");
        }
        issueReason = issueReason.trim();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");

        if (status == PendingDeliveryStatus.PENDING && (claimOperationId != null || claimedAt != null)) {
            throw new IllegalArgumentException("PENDING delivery must not contain claim metadata");
        }
        if (status == PendingDeliveryStatus.CLAIMED && (claimOperationId == null || claimedAt == null)) {
            throw new IllegalArgumentException("CLAIMED delivery requires claim metadata");
        }
    }
}
