package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Versioned launch/content configuration for ordinary-PvE pocket-Coin loss.
 *
 * <p>The bundled configuration is intentionally disabled until the actual launch death-loss number is chosen. The
 * proportional basis-point implementation is only an adapter-ready tuning shape; changing the number does not alter
 * wallet authority, Bank protection, or exactly-once settlement semantics.</p>
 */
public record PveDeathLossConfig(
        boolean enabled,
        String policyVersion,
        int lossBasisPoints
) implements PveDeathLossPolicy {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final long BASIS_POINT_DENOMINATOR = 10_000L;

    public PveDeathLossConfig {
        policyVersion = Objects.requireNonNull(policyVersion, "policyVersion").trim();
        if (!IDENTIFIER.matcher(policyVersion).matches()) {
            throw new IllegalArgumentException("policyVersion has invalid identifier format: " + policyVersion);
        }
        if (lossBasisPoints < 0 || lossBasisPoints > BASIS_POINT_DENOMINATOR) {
            throw new IllegalArgumentException("lossBasisPoints must be between 0 and 10000");
        }
        if (!enabled && lossBasisPoints != 0) {
            throw new IllegalArgumentException("disabled PvE death loss must use 0 basis points");
        }
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
}
