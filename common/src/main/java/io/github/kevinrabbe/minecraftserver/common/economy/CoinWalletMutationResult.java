package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

/** Exact idempotent result of one system credit/debit operation. */
public record CoinWalletMutationResult(
        UUID playerId,
        long amountMinor,
        long balanceMinor,
        long stateVersion,
        String reason
) {
    public CoinWalletMutationResult {
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be > 0");
        }
        if (balanceMinor < 0 || stateVersion < 0) {
            throw new IllegalArgumentException("balanceMinor/stateVersion must be >= 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
    }
}
