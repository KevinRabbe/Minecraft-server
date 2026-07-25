package io.github.kevinrabbe.minecraftserver.common.clan;

import java.util.Objects;
import java.util.UUID;

/** Result of moving one fungible quantity from a player snapshot into clan custody. */
public record ClanCommodityStorageDepositResult(
        ClanCommodityStorageSnapshot storage,
        UUID playerId,
        long depositedQuantity,
        long playerStateVersion
) {
    public ClanCommodityStorageDepositResult {
        storage = Objects.requireNonNull(storage, "storage");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (depositedQuantity <= 0 || playerStateVersion < 0) {
            throw new IllegalArgumentException("depositedQuantity must be > 0 and playerStateVersion must be >= 0");
        }
    }
}
