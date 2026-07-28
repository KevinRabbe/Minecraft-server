package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record BankUpgradeResult(
        UUID playerId,
        int previousTier,
        int newTier,
        long costMinor,
        long walletBalanceMinor,
        long walletStateVersion,
        long bankBalanceMinor,
        long bankStateVersion,
        String reason
) {
    public BankUpgradeResult {
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (previousTier < 0 || newTier != previousTier + 1 || costMinor < 0) {
            throw new IllegalArgumentException("invalid Bank Manager tier transition");
        }
        if (walletBalanceMinor < 0 || walletStateVersion < 0 || bankBalanceMinor < 0 || bankStateVersion < 0) {
            throw new IllegalArgumentException("invalid bank upgrade state values");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
    }
}
