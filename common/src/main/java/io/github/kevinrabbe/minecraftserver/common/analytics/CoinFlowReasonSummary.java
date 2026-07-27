package io.github.kevinrabbe.minecraftserver.common.analytics;

import java.math.BigInteger;
import java.util.Objects;

/** Aggregate Coin movement/supply effect for one stable reason in a read-only analytics window. */
public record CoinFlowReasonSummary(
        String reason,
        BigInteger createdMinor,
        BigInteger destroyedMinor,
        BigInteger netSupplyChangeMinor,
        BigInteger grossMovementMinor,
        long operationCount
) {
    public CoinFlowReasonSummary {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
        createdMinor = requireNonNegative(createdMinor, "createdMinor");
        destroyedMinor = requireNonNegative(destroyedMinor, "destroyedMinor");
        netSupplyChangeMinor = Objects.requireNonNull(netSupplyChangeMinor, "netSupplyChangeMinor");
        grossMovementMinor = requireNonNegative(grossMovementMinor, "grossMovementMinor");
        if (!createdMinor.subtract(destroyedMinor).equals(netSupplyChangeMinor)) {
            throw new IllegalArgumentException("netSupplyChangeMinor must equal createdMinor - destroyedMinor");
        }
        if (operationCount < 1) {
            throw new IllegalArgumentException("operationCount must be >= 1");
        }
    }

    public BigInteger supplyImpactMinor() {
        return createdMinor.add(destroyedMinor);
    }

    private static BigInteger requireNonNegative(BigInteger value, String name) {
        BigInteger nonNull = Objects.requireNonNull(value, name);
        if (nonNull.signum() < 0) {
            throw new IllegalArgumentException(name + " must be nonnegative");
        }
        return nonNull;
    }
}
