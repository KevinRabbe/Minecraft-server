package io.github.kevinrabbe.minecraftserver.common.clan;

import java.util.Objects;
import java.util.UUID;

/** Result of reserving clan commodity value into one durable player delivery. */
public record ClanCommodityStorageWithdrawResult(
        ClanCommodityStorageSnapshot storage,
        UUID playerId,
        long withdrawnQuantity,
        UUID deliveryId
) {
    public ClanCommodityStorageWithdrawResult {
        storage = Objects.requireNonNull(storage, "storage");
        playerId = Objects.requireNonNull(playerId, "playerId");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        if (withdrawnQuantity <= 0) {
            throw new IllegalArgumentException("withdrawnQuantity must be > 0");
        }
    }
}
