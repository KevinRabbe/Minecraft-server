package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record BazaarCancelResult(
        UUID orderId,
        UUID playerId,
        BazaarOrderSide side,
        long returnedMoneyMinor,
        long returnedCommodityQuantity,
        UUID commodityDeliveryId,
        long walletBalanceMinor,
        long walletStateVersion
) {
    public BazaarCancelResult {
        orderId = Objects.requireNonNull(orderId, "orderId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        side = Objects.requireNonNull(side, "side");
        if (returnedMoneyMinor < 0 || returnedCommodityQuantity < 0 || walletBalanceMinor < 0 || walletStateVersion < 0) {
            throw new IllegalArgumentException("Bazaar cancellation values must be nonnegative");
        }
        if (side == BazaarOrderSide.BUY && (returnedCommodityQuantity != 0 || commodityDeliveryId != null)) {
            throw new IllegalArgumentException("BUY cancellation cannot return commodity delivery");
        }
        if (side == BazaarOrderSide.SELL
                && (returnedMoneyMinor != 0 || (returnedCommodityQuantity > 0) != (commodityDeliveryId != null))) {
            throw new IllegalArgumentException("SELL cancellation return shape is invalid");
        }
    }
}
