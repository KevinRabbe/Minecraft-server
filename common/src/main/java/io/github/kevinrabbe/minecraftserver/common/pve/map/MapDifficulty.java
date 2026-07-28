package io.github.kevinrabbe.minecraftserver.common.pve.map;

/** Encounter-strength coordinate, deliberately independent from player skill level. */
public record MapDifficulty(int value) implements Comparable<MapDifficulty> {
    public static final int MIN_VALUE = 1;
    public static final int TECHNICAL_MAX_VALUE = 1_000_000;

    public MapDifficulty {
        if (value < MIN_VALUE || value > TECHNICAL_MAX_VALUE) {
            throw new IllegalArgumentException(
                    "map difficulty must be between " + MIN_VALUE + " and " + TECHNICAL_MAX_VALUE + ": " + value
            );
        }
    }

    @Override
    public int compareTo(MapDifficulty other) {
        return Integer.compare(value, other.value);
    }
}
