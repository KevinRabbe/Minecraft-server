package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import java.util.Objects;
import java.util.UUID;

/** Persistent state snapshot for one player's bounty contract and its frozen content version. */
public record BountyContractSnapshot(
        UUID contractId,
        UUID playerId,
        BountyFamilyId familyId,
        int tier,
        int contentVersion,
        BountyContractStatus status,
        int eligibleKillProgress,
        int requiredEligibleKills,
        int summonAuthorizationsRemaining,
        long stateVersion
) {
    public BountyContractSnapshot {
        contractId = Objects.requireNonNull(contractId, "contractId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        familyId = Objects.requireNonNull(familyId, "familyId");
        status = Objects.requireNonNull(status, "status");
        if (tier <= 0) {
            throw new IllegalArgumentException("tier must be > 0");
        }
        if (contentVersion <= 0) {
            throw new IllegalArgumentException("contentVersion must be > 0");
        }
        if (requiredEligibleKills <= 0) {
            throw new IllegalArgumentException("requiredEligibleKills must be > 0");
        }
        if (eligibleKillProgress < 0 || eligibleKillProgress > requiredEligibleKills) {
            throw new IllegalArgumentException("eligibleKillProgress out of range");
        }
        if (summonAuthorizationsRemaining < 0) {
            throw new IllegalArgumentException("summonAuthorizationsRemaining must be >= 0");
        }
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
    }
}
