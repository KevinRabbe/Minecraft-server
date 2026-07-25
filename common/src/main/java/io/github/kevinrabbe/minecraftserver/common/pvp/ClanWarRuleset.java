package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.Objects;
import java.util.regex.Pattern;

/** Frozen cheap policy values for newly-created 1.8.9 Clan Wars. */
public record ClanWarRuleset(
        String rulesetId,
        int rulesetVersion,
        int ratingPolicyVersion,
        int initialRating,
        int kFactor,
        int teamSize
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public ClanWarRuleset {
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
        if (teamSize <= 0 || teamSize > 100) {
            throw new IllegalArgumentException("teamSize must be between 1 and 100");
        }
    }

    public static ClanWarRuleset legacy189V1() {
        return new ClanWarRuleset("war.legacy_1_8_9", 1, 1, 1000, 32, 1);
    }
}
