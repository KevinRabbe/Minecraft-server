package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClanWarRosterEntry(
        UUID warId,
        UUID clanId,
        UUID playerId,
        Instant lockedAt,
        Instant releasedAt
) {
    public ClanWarRosterEntry {
        warId = Objects.requireNonNull(warId, "warId");
        clanId = Objects.requireNonNull(clanId, "clanId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        lockedAt = Objects.requireNonNull(lockedAt, "lockedAt");
    }
}
