package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Objects;

public record BountySummonLeaseResult(
        BountySummonSnapshot summon,
        String bossDefinitionId
) {
    public BountySummonLeaseResult {
        summon = Objects.requireNonNull(summon, "summon");
        if (summon.status() != BountySummonStatus.ACTIVE) {
            throw new IllegalArgumentException("summon must be ACTIVE");
        }
        if (bossDefinitionId == null || bossDefinitionId.isBlank()) {
            throw new IllegalArgumentException("bossDefinitionId must not be blank");
        }
        bossDefinitionId = bossDefinitionId.trim();
    }
}
