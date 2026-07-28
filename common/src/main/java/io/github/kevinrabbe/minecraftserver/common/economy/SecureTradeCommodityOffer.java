package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

/** One participant-owned commodity row in a secure-trade offer snapshot. */
public record SecureTradeCommodityOffer(
        UUID ownerPlayerId,
        String commodityDefinitionId,
        long quantity
) {
    public SecureTradeCommodityOffer {
        ownerPlayerId = Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        commodityDefinitionId = commodityDefinitionId.trim();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
    }
}
