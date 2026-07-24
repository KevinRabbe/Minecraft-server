package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record BazaarBuyOrderCreateResult(
        BazaarOrderSnapshot order,
        long walletBalanceMinor,
        long walletStateVersion
) {
    public BazaarBuyOrderCreateResult {
        order = Objects.requireNonNull(order, "order");
        if (order.side() != BazaarOrderSide.BUY) {
            throw new IllegalArgumentException("order must be BUY");
        }
        if (walletBalanceMinor < 0 || walletStateVersion < 0) {
            throw new IllegalArgumentException("invalid wallet result values");
        }
    }

    public UUID orderId() {
        return order.orderId();
    }
}
