package io.github.kevinrabbe.minecraftserver.common.clan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One individualized item currently held in authoritative clan storage custody. */
public record ClanUniqueStorageItemSnapshot(
        UUID clanId,
        UUID itemInstanceId,
        String definitionId,
        long itemStateVersion,
        Instant updatedAt
) {
    public ClanUniqueStorageItemSnapshot {
        clanId = Objects.requireNonNull(clanId, "clanId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        if (itemStateVersion < 0) {
            throw new IllegalArgumentException("itemStateVersion must be >= 0");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
