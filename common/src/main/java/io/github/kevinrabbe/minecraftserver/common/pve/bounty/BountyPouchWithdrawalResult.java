package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Objects;
import java.util.UUID;

/** Exactly-once pouch extraction result with one durable commodity-delivery reservation. */
public record BountyPouchWithdrawalResult(
        BountyPouchBalanceSnapshot balance,
        long withdrawnQuantity,
        UUID deliveryId
) {
    public BountyPouchWithdrawalResult {
        balance = Objects.requireNonNull(balance, "balance");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        if (withdrawnQuantity <= 0) {
            throw new IllegalArgumentException("withdrawnQuantity must be > 0");
        }
    }
}
