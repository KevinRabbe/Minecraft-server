package io.github.kevinrabbe.minecraftserver.common.pve.map;

/** Persisted-state reason a CREATED Map run is no longer capable of a valid encounter handoff. */
public enum MapEncounterRecoveryReason {
    NO_HANDOFF,
    TRANSFER_EXPIRED,
    RETURNED_TO_SOURCE,
    TARGET_START_TIMEOUT
}
