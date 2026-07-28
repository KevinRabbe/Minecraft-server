package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Player-facing read projection for one Clan War; persistent custody/value details are intentionally absent. */
public record ClanWarView(
        UUID warId,
        UUID challengerClanId,
        String challengerName,
        String challengerTag,
        UUID defenderClanId,
        String defenderName,
        String defenderTag,
        ClanWarStatus status,
        UUID winningClanId,
        int teamSize,
        int challengerRosterCount,
        int defenderRosterCount,
        String rulesetId,
        int rulesetVersion,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public ClanWarView {
        warId = Objects.requireNonNull(warId, "warId");
        challengerClanId = Objects.requireNonNull(challengerClanId, "challengerClanId");
        challengerName = requireText(challengerName, "challengerName");
        challengerTag = requireText(challengerTag, "challengerTag");
        defenderClanId = Objects.requireNonNull(defenderClanId, "defenderClanId");
        defenderName = requireText(defenderName, "defenderName");
        defenderTag = requireText(defenderTag, "defenderTag");
        status = Objects.requireNonNull(status, "status");
        rulesetId = requireText(rulesetId, "rulesetId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (challengerClanId.equals(defenderClanId)) {
            throw new IllegalArgumentException("Clan War participants must be distinct");
        }
        if (teamSize < 1 || challengerRosterCount < 0 || defenderRosterCount < 0
                || challengerRosterCount > teamSize || defenderRosterCount > teamSize || rulesetVersion < 1) {
            throw new IllegalArgumentException("invalid Clan War roster/ruleset projection");
        }
    }

    private static String requireText(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
