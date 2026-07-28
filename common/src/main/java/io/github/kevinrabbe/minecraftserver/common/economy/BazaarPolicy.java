package io.github.kevinrabbe.minecraftserver.common.economy;

/** Small explicit tuning policy for Bazaar execution. */
public record BazaarPolicy(
        int executionFeeBasisPoints,
        int maxFillsPerMatch
) {
    public BazaarPolicy {
        if (executionFeeBasisPoints < 0 || executionFeeBasisPoints > 10_000) {
            throw new IllegalArgumentException("executionFeeBasisPoints must be between 0 and 10000");
        }
        if (maxFillsPerMatch < 1 || maxFillsPerMatch > 10_000) {
            throw new IllegalArgumentException("maxFillsPerMatch must be between 1 and 10000");
        }
    }
}
