package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Map;
import java.util.Objects;

public record BountyCompletionResult(
        BountyContractSnapshot contract,
        Map<String, Long> pouchRewards
) {
    public BountyCompletionResult {
        contract = Objects.requireNonNull(contract, "contract");
        if (contract.status() != BountyContractStatus.COMPLETED) {
            throw new IllegalArgumentException("contract must be COMPLETED");
        }
        pouchRewards = Map.copyOf(Objects.requireNonNull(pouchRewards, "pouchRewards"));
        pouchRewards.forEach((definitionId, quantity) -> {
            if (definitionId == null || definitionId.isBlank() || quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("invalid bounty pouch reward");
            }
        });
    }
}
