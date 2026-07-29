package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

/** Frozen result of one committed ordinary-PvE pocket-Coin death-loss operation. */
public record PveDeathLossResult(
        UUID playerId,
        String policyVersion,
        long previousBalanceMinor,
        long previousWalletStateVersion,
        long lossMinor,
        long walletBalanceMinor,
        long walletStateVersion,
        String reason
) {
    public PveDeathLossResult {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(reason, "reason");
        if (policyVersion.isBlank() || reason.isBlank()) {
            throw new IllegalArgumentException("policyVersion and reason must not be blank");
        }
        if (previousBalanceMinor < 0 || previousWalletStateVersion < 0 || lossMinor < 0
                || walletBalanceMinor < 0 || walletStateVersion < 0) {
            throw new IllegalArgumentException("PvE death-loss values must not be negative");
        }
        if (lossMinor > previousBalanceMinor || walletBalanceMinor != previousBalanceMinor - lossMinor) {
            throw new IllegalArgumentException("PvE death-loss balance arithmetic is invalid");
        }
        long expectedVersion = lossMinor == 0 ? previousWalletStateVersion : previousWalletStateVersion + 1;
        if (walletStateVersion != expectedVersion) {
            throw new IllegalArgumentException("PvE death-loss wallet state_version transition is invalid");
        }
    }
}
