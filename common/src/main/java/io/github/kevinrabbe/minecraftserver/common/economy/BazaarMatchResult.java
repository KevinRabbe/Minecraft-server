package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;

public record BazaarMatchResult(
        String commodityDefinitionId,
        int fills,
        long quantityFilled,
        long grossTradeValueMinor,
        long feesDestroyedMinor
) {
    public BazaarMatchResult {
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        commodityDefinitionId = commodityDefinitionId.trim();
        if (fills < 0 || quantityFilled < 0 || grossTradeValueMinor < 0 || feesDestroyedMinor < 0) {
            throw new IllegalArgumentException("Bazaar match totals must be nonnegative");
        }
    }
}
