package io.github.kevinrabbe.minecraftserver.common.economy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BazaarOrderSnapshot(
        UUID orderId,
        UUID playerId,
        String commodityDefinitionId,
        BazaarOrderSide side,
        long limitPriceMinor,
        long originalQuantity,
        long remainingQuantity,
        long reservedMoneyMinor,
        BazaarOrderStatus status,
        Instant createdAt
) {
    public BazaarOrderSnapshot {
        orderId = Objects.requireNonNull(orderId, "orderId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        commodityDefinitionId = commodityDefinitionId.trim();
        side = Objects.requireNonNull(side, "side");
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (limitPriceMinor <= 0 || originalQuantity <= 0) {
            throw new IllegalArgumentException("price and original quantity must be > 0");
        }
        if (remainingQuantity < 0 || remainingQuantity > originalQuantity || reservedMoneyMinor < 0) {
            throw new IllegalArgumentException("invalid remaining/reserved Bazaar state");
        }
    }
}
