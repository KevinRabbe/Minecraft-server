package io.github.kevinrabbe.minecraftserver.common.clan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative clan identity snapshot. */
public record ClanSnapshot(
        UUID clanId,
        String name,
        String tag,
        UUID createdByPlayerId,
        long stateVersion,
        Instant createdAt,
        Instant updatedAt
) {
    public ClanSnapshot {
        clanId = Objects.requireNonNull(clanId, "clanId");
        if (name == null || name.isBlank() || tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("name/tag must not be blank");
        }
        createdByPlayerId = Objects.requireNonNull(createdByPlayerId, "createdByPlayerId");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
