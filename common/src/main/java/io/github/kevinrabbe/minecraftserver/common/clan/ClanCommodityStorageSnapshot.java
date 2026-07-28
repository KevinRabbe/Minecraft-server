package io.github.kevinrabbe.minecraftserver.common.clan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authoritative quantity/version snapshot for one commodity in clan storage. */
public record ClanCommodityStorageSnapshot(
        UUID clanId,
        String commodityDefinitionId,
        long quantity,
        long stateVersion,
        Instant updatedAt
) {
    public ClanCommodityStorageSnapshot {
        clanId = Objects.requireNonNull(clanId, "clanId");
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        if (quantity < 0 || stateVersion < 0) {
            throw new IllegalArgumentException("quantity and stateVersion must be >= 0");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
