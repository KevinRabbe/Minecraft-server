package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

/** Result of an atomic wallet<->protected-bank transfer. */
public record BankTransferResult(
        UUID playerId,
        long amountMinor,
        long walletBalanceMinor,
        long walletStateVersion,
        long bankBalanceMinor,
        long bankStateVersion,
        String reason
) {
    public BankTransferResult {
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (amountMinor <= 0 || walletBalanceMinor < 0 || walletStateVersion < 0 || bankBalanceMinor < 0 || bankStateVersion < 0) {
            throw new IllegalArgumentException("invalid bank transfer result values");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
    }
}
