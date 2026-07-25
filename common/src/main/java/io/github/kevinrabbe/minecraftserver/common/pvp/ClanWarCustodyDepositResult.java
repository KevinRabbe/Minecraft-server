package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.Objects;

public record ClanWarCustodyDepositResult(
        ClanWarCustodiedItemSnapshot item,
        long playerStateVersion
) {
    public ClanWarCustodyDepositResult {
        item = Objects.requireNonNull(item, "item");
        if (playerStateVersion < 0) {
            throw new IllegalArgumentException("playerStateVersion must be >= 0");
        }
    }
}
