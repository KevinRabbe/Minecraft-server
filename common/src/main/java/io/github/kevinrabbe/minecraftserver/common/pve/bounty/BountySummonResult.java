package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Objects;
import java.util.UUID;

public record BountySummonResult(
        UUID contractId,
        UUID playerId,
        BountyFamilyId familyId,
        int tier,
        String bossDefinitionId,
        long stateVersion
) {
    public BountySummonResult {
        contractId = Objects.requireNonNull(contractId, "contractId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        familyId = Objects.requireNonNull(familyId, "familyId");
        if (tier <= 0 || stateVersion < 0) {
            throw new IllegalArgumentException("invalid bounty summon values");
        }
        if (bossDefinitionId == null || bossDefinitionId.isBlank()) {
            throw new IllegalArgumentException("bossDefinitionId must not be blank");
        }
        bossDefinitionId = bossDefinitionId.trim();
    }
}
