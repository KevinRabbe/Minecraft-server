package io.github.kevinrabbe.minecraftserver.common.clan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current authoritative protected Coin custody for one clan. */
public record ClanTreasurySnapshot(
        UUID clanId,
        long balanceMinor,
        long stateVersion,
        Instant updatedAt
) {
    public ClanTreasurySnapshot {
        clanId = Objects.requireNonNull(clanId, "clanId");
        if (balanceMinor < 0 || stateVersion < 0) {
            throw new IllegalArgumentException("treasury balance/stateVersion must be nonnegative");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
