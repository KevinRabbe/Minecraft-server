package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

/** Persistent lifecycle for one player's bounty contract. */
public enum BountyContractStatus {
    ACTIVE_HUNT,
    SUMMON_READY,
    SUMMONED,
    COMPLETED,
    FAILED,
    CANCELLED
}
