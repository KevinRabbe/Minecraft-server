package io.github.kevinrabbe.minecraftserver.common.item;

/**
 * Persistent normalized intrinsic roll quality.
 * Uses basis points (0..10_000) so item quality remains deterministic across balance changes.
 */
public record RollQuality(int basisPoints) implements Comparable<RollQuality> {
    public static final int MIN_BASIS_POINTS = 0;
    public static final int MAX_BASIS_POINTS = 10_000;
    public static final RollQuality MIN = new RollQuality(MIN_BASIS_POINTS);
    public static final RollQuality PERFECT = new RollQuality(MAX_BASIS_POINTS);

    public RollQuality {
        if (basisPoints < MIN_BASIS_POINTS || basisPoints > MAX_BASIS_POINTS) {
            throw new IllegalArgumentException("basisPoints must be between 0 and 10000: " + basisPoints);
        }
    }

    public double fraction() {
        return basisPoints / 10_000.0d;
    }

    public boolean perfect() {
        return basisPoints == MAX_BASIS_POINTS;
    }

    @Override
    public int compareTo(RollQuality other) {
        return Integer.compare(basisPoints, other.basisPoints);
    }
}
