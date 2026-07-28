package io.github.kevinrabbe.minecraftserver.common.world;

/** Persistent lifecycle for one player-directed expansion vote. */
public enum ExpansionVoteStatus {
    SCHEDULED,
    OPEN,
    RESOLVED,
    CANCELLED
}
