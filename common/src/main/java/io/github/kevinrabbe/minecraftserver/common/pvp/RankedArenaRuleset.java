package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned competitive ruleset plus the cheap tuning values used by the rating authority. */
public record RankedArenaRuleset(
        String rulesetId,
        int rulesetVersion,
        int ratingPolicyVersion,
        int initialRating,
        int kFactor
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public RankedArenaRuleset {
        rulesetId = Objects.requireNonNull(rulesetId, "rulesetId").trim();
        if (!ID.matcher(rulesetId).matches()) {
            throw new IllegalArgumentException("rulesetId must be a stable lowercase identifier");
        }
        if (rulesetVersion < 1 || ratingPolicyVersion < 1) {
            throw new IllegalArgumentException("ruleset/rating policy versions must be >= 1");
        }
        if (initialRating < 0) {
            throw new IllegalArgumentException("initialRating must be >= 0");
        }
        if (kFactor <= 0 || kFactor > 10_000) {
            throw new IllegalArgumentException("kFactor must be between 1 and 10000");
        }
    }

    public static RankedArenaRuleset legacy189V1() {
        return new RankedArenaRuleset("arena.legacy_1_8_9", 1, 1, 1000, 32);
    }
}
