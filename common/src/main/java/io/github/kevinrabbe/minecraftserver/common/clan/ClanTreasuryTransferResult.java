package io.github.kevinrabbe.minecraftserver.common.clan;

import java.util.Objects;
import java.util.UUID;

/** Exactly-once result of one player ↔ clan treasury Coin transfer. */
public record ClanTreasuryTransferResult(
        ClanTreasurySnapshot treasury,
        UUID playerId,
        long amountMinor,
        long walletBalanceMinor,
        long walletStateVersion
) {
    public ClanTreasuryTransferResult {
        treasury = Objects.requireNonNull(treasury, "treasury");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (amountMinor <= 0 || walletBalanceMinor < 0 || walletStateVersion < 0) {
            throw new IllegalArgumentException("invalid treasury transfer result values");
        }
    }
}
