package io.github.kevinrabbe.minecraftserver.common.analytics;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

/** Read-only current-book and observed execution summary for one Bazaar commodity. */
public record BazaarMarketSummary(
        String commodityDefinitionId,
        Instant snapshotAt,
        Instant historyWindowStart,
        Instant historyWindowEnd,
        Instant historyObservedThrough,
        Long bestBidPriceMinor,
        Long bestAskPriceMinor,
        BigInteger bestBidQuantity,
        BigInteger bestAskQuantity,
        long openBuyOrderCount,
        long openSellOrderCount,
        BigInteger openBuyQuantity,
        BigInteger openSellQuantity,
        long matchPassCount,
        long fillCount,
        BigInteger filledQuantity,
        BigInteger grossTradeValueMinor,
        BigInteger feesDestroyedMinor
) {
    public BazaarMarketSummary {
        commodityDefinitionId = requireNonBlank(commodityDefinitionId, "commodityDefinitionId");
        snapshotAt = Objects.requireNonNull(snapshotAt, "snapshotAt");
        historyWindowStart = Objects.requireNonNull(historyWindowStart, "historyWindowStart");
        historyWindowEnd = Objects.requireNonNull(historyWindowEnd, "historyWindowEnd");
        historyObservedThrough = Objects.requireNonNull(historyObservedThrough, "historyObservedThrough");
        if (!historyWindowEnd.isAfter(historyWindowStart)) {
            throw new IllegalArgumentException("historyWindowEnd must be after historyWindowStart");
        }
        if (historyObservedThrough.isBefore(historyWindowStart) || historyObservedThrough.isAfter(historyWindowEnd)) {
            throw new IllegalArgumentException("historyObservedThrough must be inside the requested history window");
        }
        if (bestBidPriceMinor != null && bestBidPriceMinor < 0) {
            throw new IllegalArgumentException("bestBidPriceMinor must be nonnegative when present");
        }
        if (bestAskPriceMinor != null && bestAskPriceMinor < 0) {
            throw new IllegalArgumentException("bestAskPriceMinor must be nonnegative when present");
        }
        bestBidQuantity = requireNonNegative(bestBidQuantity, "bestBidQuantity");
        bestAskQuantity = requireNonNegative(bestAskQuantity, "bestAskQuantity");
        openBuyQuantity = requireNonNegative(openBuyQuantity, "openBuyQuantity");
        openSellQuantity = requireNonNegative(openSellQuantity, "openSellQuantity");
        filledQuantity = requireNonNegative(filledQuantity, "filledQuantity");
        grossTradeValueMinor = requireNonNegative(grossTradeValueMinor, "grossTradeValueMinor");
        feesDestroyedMinor = requireNonNegative(feesDestroyedMinor, "feesDestroyedMinor");
        if (openBuyOrderCount < 0 || openSellOrderCount < 0 || matchPassCount < 0 || fillCount < 0) {
            throw new IllegalArgumentException("Bazaar market counts must be nonnegative");
        }
        if (bestBidPriceMinor == null && bestBidQuantity.signum() != 0) {
            throw new IllegalArgumentException("bestBidQuantity requires a best bid price");
        }
        if (bestAskPriceMinor == null && bestAskQuantity.signum() != 0) {
            throw new IllegalArgumentException("bestAskQuantity requires a best ask price");
        }
        if ((openBuyOrderCount == 0) != (bestBidPriceMinor == null)) {
            throw new IllegalArgumentException("best bid presence must match open buy-order presence");
        }
        if ((openSellOrderCount == 0) != (bestAskPriceMinor == null)) {
            throw new IllegalArgumentException("best ask presence must match open sell-order presence");
        }
        if (bestBidQuantity.compareTo(openBuyQuantity) > 0 || bestAskQuantity.compareTo(openSellQuantity) > 0) {
            throw new IllegalArgumentException("best-level depth cannot exceed total open depth");
        }
    }

    /** Returns null when either side of the book is absent. Negative means the current book is crossed. */
    public BigInteger quotedSpreadMinor() {
        if (bestBidPriceMinor == null || bestAskPriceMinor == null) {
            return null;
        }
        return BigInteger.valueOf(bestAskPriceMinor).subtract(BigInteger.valueOf(bestBidPriceMinor));
    }

    private static BigInteger requireNonNegative(BigInteger value, String name) {
        BigInteger nonNull = Objects.requireNonNull(value, name);
        if (nonNull.signum() < 0) {
            throw new IllegalArgumentException(name + " must be nonnegative");
        }
        return nonNull;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
