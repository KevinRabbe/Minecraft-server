package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Objects;
import java.util.UUID;

/** Immutable result binding one eligible managed kill operation to either one contract revision or a permanent no-op. */
public record BountyKillProgressResult(
        UUID playerId,
        BountyFamilyId familyId,
        int eligibleKills,
        BountyContractSnapshot contract
) {
    public BountyKillProgressResult {
        playerId = Objects.requireNonNull(playerId, "playerId");
        familyId = Objects.requireNonNull(familyId, "familyId");
        if (eligibleKills <= 0) {
            throw new IllegalArgumentException("eligibleKills must be > 0");
        }
        if (contract != null) {
            if (!contract.playerId().equals(playerId) || !contract.familyId().equals(familyId)) {
                throw new IllegalArgumentException("applied bounty contract does not match player/family");
            }
        }
    }

    public boolean applied() {
        return contract != null;
    }
}
