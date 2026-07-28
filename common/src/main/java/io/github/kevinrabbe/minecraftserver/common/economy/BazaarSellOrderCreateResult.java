package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record BazaarSellOrderCreateResult(
        BazaarOrderSnapshot order,
        long playerStateVersion
) {
    public BazaarSellOrderCreateResult {
        order = Objects.requireNonNull(order, "order");
        if (order.side() != BazaarOrderSide.SELL) {
            throw new IllegalArgumentException("order must be SELL");
        }
        if (playerStateVersion < 0) {
            throw new IllegalArgumentException("playerStateVersion must be >= 0");
        }
    }

    public UUID orderId() {
        return order.orderId();
    }
}
