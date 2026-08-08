package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned launch/content configuration for ordinary persistent-combat pocket-Coin loss. */
public record PveDeathLossConfig(
        boolean enabled,
        String policyVersion,
        int lossBasisPoints,
        List<String> zoneIds
) implements PveDeathLossPolicy {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final long BASIS_POINT_DENOMINATOR = 10_000L;
    private static final int MAX_ZONE_IDS = 64;

    public PveDeathLossConfig {
        policyVersion = requireIdentifier(policyVersion, "policyVersion");
        if (lossBasisPoints < 0 || lossBasisPoints > BASIS_POINT_DENOMINATOR) {
            throw new IllegalArgumentException("lossBasisPoints must be between 0 and 10000");
        }
        zoneIds = List.copyOf(Objects.requireNonNull(zoneIds, "zoneIds")).stream()
                .map(value -> requireIdentifier(value, "zoneId"))
                .distinct()
                .sorted()
                .toList();
        if (zoneIds.size() > MAX_ZONE_IDS) {
            throw new IllegalArgumentException("zoneIds must contain at most " + MAX_ZONE_IDS + " entries");
        }
        if (!enabled && (lossBasisPoints != 0 || !zoneIds.isEmpty())) {
            throw new IllegalArgumentException("disabled PvE death loss must use 0 basis points and no zones");
        }
        if (enabled && (lossBasisPoints == 0 || zoneIds.isEmpty())) {
            throw new IllegalArgumentException("enabled PvE death loss requires a positive loss and at least one zone");
        }
    }

    /** True only for an explicitly authored normal-world combat zone opted into this policy. */
    public boolean appliesToZone(String zoneId) {
        if (!enabled || zoneId == null || zoneId.isBlank()) {
            return false;
        }
        return zoneIds.contains(zoneId.trim());
    }

    @Override
    public long lossMinor(long lockedPocketBalanceMinor) {
        if (lockedPocketBalanceMinor < 0) {
            throw new IllegalArgumentException("lockedPocketBalanceMinor must be >= 0");
        }
        if (!enabled || lockedPocketBalanceMinor == 0 || lossBasisPoints == 0) {
            return 0L;
        }

        // Avoid overflow from balance * basisPoints while preserving exact floor(balance * bps / 10000).
        long whole = lockedPocketBalanceMinor / BASIS_POINT_DENOMINATOR;
        long remainder = lockedPocketBalanceMinor % BASIS_POINT_DENOMINATOR;
        long wholeLoss = Math.multiplyExact(whole, lossBasisPoints);
        long remainderLoss = (remainder * lossBasisPoints) / BASIS_POINT_DENOMINATOR;
        return Math.addExact(wholeLoss, remainderLoss);
    }

    private static String requireIdentifier(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " has invalid identifier format: " + normalized);
        }
        return normalized;
    }
}
