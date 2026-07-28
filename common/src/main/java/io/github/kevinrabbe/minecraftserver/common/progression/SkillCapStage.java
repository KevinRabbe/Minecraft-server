package io.github.kevinrabbe.minecraftserver.common.progression;

/** Locked staged active-cap progression. Timing of stage changes remains content/world progression. */
public enum SkillCapStage {
    LAUNCH(50),
    EXPANSION_75(75),
    LATE_100(100);

    private final int activeCap;

    SkillCapStage(int activeCap) {
        this.activeCap = activeCap;
    }

    public int activeCap() {
        return activeCap;
    }

    public static SkillCapStage fromActiveCap(int activeCap) {
        for (SkillCapStage stage : values()) {
            if (stage.activeCap == activeCap) {
                return stage;
            }
        }
        throw new IllegalArgumentException("Unsupported active skill cap: " + activeCap);
    }
}
