package io.github.kevinrabbe.minecraftserver.common.economy;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Persistent protected-bank state for one player. */
public record BankAccountSnapshot(
        UUID playerId,
        long balanceMinor,
        int tier,
        long stateVersion,
        LocalDate lastInterestPeriod
) {
    public BankAccountSnapshot {
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (balanceMinor < 0) {
            throw new IllegalArgumentException("balanceMinor must be >= 0");
        }
        if (tier < 0) {
            throw new IllegalArgumentException("tier must be >= 0");
        }
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
    }

    public void requireWithin(BankTierDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.tier() != tier) {
            throw new IllegalArgumentException("bank tier definition does not match account tier");
        }
        if (balanceMinor > definition.capacityMinor()) {
            throw new IllegalStateException("bank balance exceeds configured tier capacity");
        }
    }
}
