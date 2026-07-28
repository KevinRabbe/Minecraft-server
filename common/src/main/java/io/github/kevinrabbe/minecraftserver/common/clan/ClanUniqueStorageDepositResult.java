package io.github.kevinrabbe.minecraftserver.common.clan;

import java.util.Objects;
import java.util.UUID;

/** Result of moving one individualized item from player inventory into clan custody. */
public record ClanUniqueStorageDepositResult(
        UUID clanId,
        UUID playerId,
        UUID itemInstanceId,
        long itemStateVersion,
        long playerStateVersion
) {
    public ClanUniqueStorageDepositResult {
        clanId = Objects.requireNonNull(clanId, "clanId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (itemStateVersion < 0 || playerStateVersion < 0) {
            throw new IllegalArgumentException("state versions must be >= 0");
        }
    }
}
