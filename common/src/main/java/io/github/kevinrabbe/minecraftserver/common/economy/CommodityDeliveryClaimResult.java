package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record CommodityDeliveryClaimResult(
        UUID deliveryId,
        UUID playerId,
        String commodityDefinitionId,
        long quantity,
        long playerStateVersion
) {
    public CommodityDeliveryClaimResult {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        commodityDefinitionId = commodityDefinitionId.trim();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (playerStateVersion < 0) {
            throw new IllegalArgumentException("playerStateVersion must be >= 0");
        }
    }
}
