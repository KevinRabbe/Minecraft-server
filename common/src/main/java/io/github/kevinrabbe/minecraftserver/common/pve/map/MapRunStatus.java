package io.github.kevinrabbe.minecraftserver.common.pve.map;

/** Persistent lifecycle for one execution of one opened Map item. */
public enum MapRunStatus {
    CREATED,
    ACTIVE,
    COMPLETED,
    FAILED,
    CLOSED
}
