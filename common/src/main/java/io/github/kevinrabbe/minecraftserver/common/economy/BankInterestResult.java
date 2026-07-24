package io.github.kevinrabbe.minecraftserver.common.economy;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record BankInterestResult(
        UUID playerId,
        LocalDate interestPeriod,
        long creditedMinor,
        long bankBalanceMinor,
        long bankStateVersion,
        String reason
) {
    public BankInterestResult {
        playerId = Objects.requireNonNull(playerId, "playerId");
        interestPeriod = Objects.requireNonNull(interestPeriod, "interestPeriod");
        if (creditedMinor < 0 || bankBalanceMinor < 0 || bankStateVersion < 0) {
            throw new IllegalArgumentException("invalid bank interest result values");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
    }
}
