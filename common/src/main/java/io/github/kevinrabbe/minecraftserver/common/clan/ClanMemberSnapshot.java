package io.github.kevinrabbe.minecraftserver.common.clan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative clan membership snapshot. */
public record ClanMemberSnapshot(
        UUID clanId,
        UUID playerId,
        ClanRole role,
        Instant joinedAt
) {
    public ClanMemberSnapshot {
        clanId = Objects.requireNonNull(clanId, "clanId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        role = Objects.requireNonNull(role, "role");
        joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
    }
}
