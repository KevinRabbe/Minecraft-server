package io.github.kevinrabbe.minecraftserver.common.analytics;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Read-only Coin supply/movement summary derived from append-only economic evidence. */
public record CoinFlowSummary(
        Instant windowStart,
        Instant windowEnd,
        Instant observedThrough,
        BigInteger createdMinor,
        BigInteger destroyedMinor,
        BigInteger netSupplyChangeMinor,
        BigInteger grossMovementMinor,
        long operationCount,
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
        createdMinor = requireNonNegative(createdMinor, "createdMinor");
        destroyedMinor = requireNonNegative(destroyedMinor, "destroyedMinor");
        netSupplyChangeMinor = Objects.requireNonNull(netSupplyChangeMinor, "netSupplyChangeMinor");
        grossMovementMinor = requireNonNegative(grossMovementMinor, "grossMovementMinor");
        if (!createdMinor.subtract(destroyedMinor).equals(netSupplyChangeMinor)) {
            throw new IllegalArgumentException("netSupplyChangeMinor must equal createdMinor - destroyedMinor");
        }
        if (operationCount < 0) {
            throw new IllegalArgumentException("operationCount must be nonnegative");
        }
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }

    private static BigInteger requireNonNegative(BigInteger value, String name) {
        BigInteger nonNull = Objects.requireNonNull(value, name);
        if (nonNull.signum() < 0) {
            throw new IllegalArgumentException(name + " must be nonnegative");
        }
        return nonNull;
    }
}
