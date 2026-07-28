package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

/** One durable pending commodity return created by salvage. */
public record SalvageCommodityReturn(
        UUID deliveryId,
        String commodityDefinitionId,
        long quantity
) {
    public SalvageCommodityReturn {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
    }
}
