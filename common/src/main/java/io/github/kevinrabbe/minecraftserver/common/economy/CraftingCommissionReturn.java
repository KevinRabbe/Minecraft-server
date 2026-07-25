package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

/** One durable material-return delivery created when an OPEN commission is cancelled. */
public record CraftingCommissionReturn(
        UUID deliveryId,
        String commodityDefinitionId,
        long quantity
) {
    public CraftingCommissionReturn {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
    }
}
