package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One player-owned bounty-family commodity balance before materialization into ordinary inventory delivery. */
public record BountyPouchBalanceSnapshot(
        UUID playerId,
        BountyFamilyId familyId,
        String commodityDefinitionId,
        long quantity,
        long stateVersion,
        Instant updatedAt
) {
    public BountyPouchBalanceSnapshot {
        playerId = Objects.requireNonNull(playerId, "playerId");
        familyId = Objects.requireNonNull(familyId, "familyId");
        if (commodityDefinitionId == null || commodityDefinitionId.isBlank()) {
            throw new IllegalArgumentException("commodityDefinitionId must not be blank");
        }
        commodityDefinitionId = commodityDefinitionId.trim();
        if (quantity < 0 || stateVersion < 0) {
            throw new IllegalArgumentException("quantity/stateVersion must be nonnegative");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
