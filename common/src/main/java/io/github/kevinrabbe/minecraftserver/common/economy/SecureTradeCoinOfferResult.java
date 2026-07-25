package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record SecureTradeCoinOfferResult(
        SecureTradeSnapshot trade,
        UUID playerId,
        long escrowAmountMinor,
        long walletBalanceMinor,
        long walletStateVersion
) {
    public SecureTradeCoinOfferResult {
        trade = Objects.requireNonNull(trade, "trade");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (!trade.participant(playerId)) {
            throw new IllegalArgumentException("playerId must be a trade participant");
        }
        if (escrowAmountMinor < 0 || walletBalanceMinor < 0 || walletStateVersion < 0) {
            throw new IllegalArgumentException("secure trade Coin values must be nonnegative");
        }
    }
}
