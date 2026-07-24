package io.github.kevinrabbe.minecraftserver.common.item;

/**
 * Configured relative stat-value range in basis points of a definition's base value.
 * Example: 10_000..12_000 represents base value through +20% at perfect quality.
 */
public record RollRange(int minimumBasisPoints, int maximumBasisPoints) {
    private static final int TECHNICAL_MAX_BASIS_POINTS = 1_000_000;

    public RollRange {
        if (minimumBasisPoints < 0) {
            throw new IllegalArgumentException("minimumBasisPoints must be >= 0");
        }
        if (maximumBasisPoints < minimumBasisPoints) {
            throw new IllegalArgumentException("maximumBasisPoints must be >= minimumBasisPoints");
        }
        if (maximumBasisPoints > TECHNICAL_MAX_BASIS_POINTS) {
            throw new IllegalArgumentException("maximumBasisPoints exceeds technical bound");
        }
    }

    public int interpolate(RollQuality quality) {
        long span = (long) maximumBasisPoints - minimumBasisPoints;
        long scaled = minimumBasisPoints + (span * quality.basisPoints()) / RollQuality.MAX_BASIS_POINTS;
        return Math.toIntExact(scaled);
    }
}
