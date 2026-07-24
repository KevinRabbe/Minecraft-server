package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

/** Exact idempotent result of one player-to-player Coin transfer. */
public record CoinTransferResult(
        UUID fromPlayerId,
        UUID toPlayerId,
        long amountMinor,
        long fromBalanceMinor,
        long fromStateVersion,
        long toBalanceMinor,
        long toStateVersion,
        String reason
) {
    public CoinTransferResult {
        fromPlayerId = Objects.requireNonNull(fromPlayerId, "fromPlayerId");
        toPlayerId = Objects.requireNonNull(toPlayerId, "toPlayerId");
        if (fromPlayerId.equals(toPlayerId)) {
            throw new IllegalArgumentException("Coin transfer requires distinct players");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be > 0");
        }
        if (fromBalanceMinor < 0 || fromStateVersion < 0 || toBalanceMinor < 0 || toStateVersion < 0) {
            throw new IllegalArgumentException("balances/versions must be >= 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
    }
}
