package io.github.kevinrabbe.minecraftserver.common.economy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable ownership of fungible value that has not yet been rendered back into Minecraft inventory. */
public record CommodityDeliverySnapshot(
        UUID deliveryId,
        UUID playerId,
        String commodityDefinitionId,
        long quantity,
        UUID sourceOperationId,
        CommodityDeliveryStatus status,
        UUID claimOperationId,
        Instant createdAt,
        Instant claimedAt
) {
    public CommodityDeliverySnapshot {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        commodityDefinitionId = commodityDefinitionId.trim();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        sourceOperationId = Objects.requireNonNull(sourceOperationId, "sourceOperationId");
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (status == CommodityDeliveryStatus.PENDING && (claimOperationId != null || claimedAt != null)) {
            throw new IllegalArgumentException("PENDING delivery must not have claim metadata");
        }
        if (status == CommodityDeliveryStatus.CLAIMED && (claimOperationId == null || claimedAt == null)) {
            throw new IllegalArgumentException("CLAIMED delivery requires claim metadata");
        }
    }
}
