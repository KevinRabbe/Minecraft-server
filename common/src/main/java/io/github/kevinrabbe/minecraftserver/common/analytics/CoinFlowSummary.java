package io.github.kevinrabbe.minecraftserver.common.analytics;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Read-only Coin flow summary derived from authoritative economic evidence.
 *
 * <p>Supply values are confirmed classifications only. They are complete only when
 * {@link #supplyClassificationComplete()} is true; unknown future/malformed Coin-bearing operation types remain
 * visible through the unclassified coverage fields rather than being guessed as faucets, sinks, or neutral custody.</p>
 */
public record CoinFlowSummary(
        Instant windowStart,
        Instant windowEnd,
        Instant observedThrough,
        BigInteger confirmedCreatedMinor,
        BigInteger confirmedDestroyedMinor,
        BigInteger confirmedNetSupplyChangeMinor,
        BigInteger grossMovementMinor,
        long classifiedOperationCount,
        long unclassifiedOperationCount,
        BigInteger unclassifiedGrossMovementMinor,
        List<CoinFlowReasonSummary> reasons,
        boolean reasonsTruncated
) {
    public CoinFlowSummary {
        windowStart = Objects.requireNonNull(windowStart, "windowStart");
        windowEnd = Objects.requireNonNull(windowEnd, "windowEnd");
        observedThrough = Objects.requireNonNull(observedThrough, "observedThrough");
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("windowEnd must be after windowStart");
        }
        if (observedThrough.isBefore(windowStart) || observedThrough.isAfter(windowEnd)) {
            throw new IllegalArgumentException("observedThrough must be inside the requested window");
        }
        confirmedCreatedMinor = requireNonNegative(confirmedCreatedMinor, "confirmedCreatedMinor");
        confirmedDestroyedMinor = requireNonNegative(confirmedDestroyedMinor, "confirmedDestroyedMinor");
        confirmedNetSupplyChangeMinor = Objects.requireNonNull(
                confirmedNetSupplyChangeMinor,
                "confirmedNetSupplyChangeMinor"
        );
        grossMovementMinor = requireNonNegative(grossMovementMinor, "grossMovementMinor");
        unclassifiedGrossMovementMinor = requireNonNegative(
                unclassifiedGrossMovementMinor,
                "unclassifiedGrossMovementMinor"
        );
        if (!confirmedCreatedMinor.subtract(confirmedDestroyedMinor).equals(confirmedNetSupplyChangeMinor)) {
            throw new IllegalArgumentException(
                    "confirmedNetSupplyChangeMinor must equal confirmedCreatedMinor - confirmedDestroyedMinor"
            );
        }
        if (classifiedOperationCount < 0 || unclassifiedOperationCount < 0) {
            throw new IllegalArgumentException("Coin flow operation counts must be nonnegative");
        }
        if (unclassifiedGrossMovementMinor.compareTo(grossMovementMinor) > 0) {
            throw new IllegalArgumentException("unclassified gross movement cannot exceed total gross movement");
        }
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }

    public long operationCount() {
        return Math.addExact(classifiedOperationCount, unclassifiedOperationCount);
    }

    public boolean supplyClassificationComplete() {
        return unclassifiedOperationCount == 0;
    }

    private static BigInteger requireNonNegative(BigInteger value, String name) {
        BigInteger nonNull = Objects.requireNonNull(value, name);
        if (nonNull.signum() < 0) {
            throw new IllegalArgumentException(name + " must be nonnegative");
        }
        return nonNull;
    }
}
