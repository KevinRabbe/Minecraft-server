package io.github.kevinrabbe.minecraftserver.common.economy;

/** Configured Bank Manager tier. Currency amounts are fixed-point minor units. */
public record BankTierDefinition(
        int tier,
        long capacityMinor,
        long upgradeCostMinor,
        int dailyInterestBasisPoints
) {
    private static final int MAX_INTEREST_BASIS_POINTS = 10_000;

    /** Convenience form for tiers whose upgrade cost is configured elsewhere/zero in tests. */
    public BankTierDefinition(int tier, long capacityMinor, int dailyInterestBasisPoints) {
        this(tier, capacityMinor, 0L, dailyInterestBasisPoints);
    }

    public BankTierDefinition {
        if (tier < 0) {
            throw new IllegalArgumentException("tier must be >= 0");
        }
        if (capacityMinor < 0) {
            throw new IllegalArgumentException("capacityMinor must be >= 0");
        }
        if (upgradeCostMinor < 0) {
            throw new IllegalArgumentException("upgradeCostMinor must be >= 0");
        }
        if (tier == 0 && upgradeCostMinor != 0) {
            throw new IllegalArgumentException("tier 0 upgradeCostMinor must be 0");
        }
        if (dailyInterestBasisPoints < 0 || dailyInterestBasisPoints > MAX_INTEREST_BASIS_POINTS) {
            throw new IllegalArgumentException(
                    "dailyInterestBasisPoints must be between 0 and 10000: " + dailyInterestBasisPoints
            );
        }
    }
}
