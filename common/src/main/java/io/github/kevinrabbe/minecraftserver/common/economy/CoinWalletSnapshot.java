package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record CoinWalletSnapshot(
        UUID playerId,
        long balanceMinor,
        long stateVersion
) {
    public CoinWalletSnapshot {
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (balanceMinor < 0) {
            throw new IllegalArgumentException("balanceMinor must be >= 0");
        }
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
    }
}
