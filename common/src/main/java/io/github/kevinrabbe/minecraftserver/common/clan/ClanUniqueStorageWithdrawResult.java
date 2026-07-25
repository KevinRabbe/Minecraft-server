package io.github.kevinrabbe.minecraftserver.common.clan;

import java.util.Objects;
import java.util.UUID;

/** Result of moving one clan-held individualized item into durable pending player delivery. */
public record ClanUniqueStorageWithdrawResult(
        UUID clanId,
        UUID playerId,
        UUID itemInstanceId,
        long itemStateVersion,
        UUID deliveryId
) {
    public ClanUniqueStorageWithdrawResult {
        clanId = Objects.requireNonNull(clanId, "clanId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        if (itemStateVersion < 0) {
            throw new IllegalArgumentException("itemStateVersion must be >= 0");
        }
    }
}
