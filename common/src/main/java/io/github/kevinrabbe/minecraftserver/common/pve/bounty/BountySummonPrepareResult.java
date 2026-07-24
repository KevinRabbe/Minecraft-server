package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Objects;

public record BountySummonPrepareResult(
        BountyContractSnapshot contract,
        BountySummonSnapshot summon,
        String bossDefinitionId
) {
    public BountySummonPrepareResult {
        contract = Objects.requireNonNull(contract, "contract");
        summon = Objects.requireNonNull(summon, "summon");
        if (contract.status() != BountyContractStatus.SUMMONED || summon.status() != BountySummonStatus.READY) {
            throw new IllegalArgumentException("prepared bounty summon requires SUMMONED contract and READY summon");
        }
        if (!contract.contractId().equals(summon.contractId())) {
            throw new IllegalArgumentException("contract/summon mismatch");
        }
        if (bossDefinitionId == null || bossDefinitionId.isBlank()) {
            throw new IllegalArgumentException("bossDefinitionId must not be blank");
        }
        bossDefinitionId = bossDefinitionId.trim();
    }
}
