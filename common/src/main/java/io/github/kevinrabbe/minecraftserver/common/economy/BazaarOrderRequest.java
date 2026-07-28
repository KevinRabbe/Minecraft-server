package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validated immutable request shape shared by Bazaar application/persistence adapters. */
public record BazaarOrderRequest(
        String commodityDefinitionId,
        BazaarOrderSide side,
        long quantity,
        long limitPriceMinor
) {
    private static final Pattern DEFINITION_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public BazaarOrderRequest {
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        commodityDefinitionId = commodityDefinitionId.trim();
        if (!DEFINITION_ID.matcher(commodityDefinitionId).matches()) {
            throw new IllegalArgumentException("commodityDefinitionId has invalid format: " + commodityDefinitionId);
        }
        side = Objects.requireNonNull(side, "side");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (limitPriceMinor <= 0) {
            throw new IllegalArgumentException("limitPriceMinor must be > 0");
        }
        Math.multiplyExact(quantity, limitPriceMinor);
    }

    public long maximumNotionalMinor() {
        return Math.multiplyExact(quantity, limitPriceMinor);
    }
}
