package io.github.kevinrabbe.minecraftserver.common.item;

/** Player investment applied after item creation; separate from intrinsic roll quality. */
public record UpgradeState(int level) {
    public static final UpgradeState NONE = new UpgradeState(0);
    private static final int TECHNICAL_MAX_LEVEL = 10_000;

    public UpgradeState {
        if (level < 0 || level > TECHNICAL_MAX_LEVEL) {
            throw new IllegalArgumentException("upgrade level out of supported range: " + level);
        }
    }
}
