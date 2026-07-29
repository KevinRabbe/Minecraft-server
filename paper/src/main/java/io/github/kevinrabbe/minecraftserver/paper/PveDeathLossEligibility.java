package io.github.kevinrabbe.minecraftserver.paper;

/** Pure classification for whether one Paper death signal belongs to ordinary persistent-world PvE loss. */
final class PveDeathLossEligibility {
    private PveDeathLossEligibility() {
    }

    static boolean shouldApply(boolean enabled, boolean ordinaryPersistentZone, boolean playerKillerPresent) {
        return enabled && ordinaryPersistentZone && !playerKillerPresent;
    }
}
