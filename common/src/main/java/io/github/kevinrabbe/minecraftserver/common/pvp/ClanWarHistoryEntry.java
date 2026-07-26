package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable read projection of one authoritative completed 1.8.9 Clan War. */
public record ClanWarHistoryEntry(
        UUID warId,
        UUID challengerClanId,
        String challengerName,
        String challengerTag,
        UUID defenderClanId,
        String defenderName,
        String defenderTag,
        UUID winningClanId,
        UUID losingClanId,
        int challengerRatingBefore,
        int challengerRatingAfter,
        int defenderRatingBefore,
        int defenderRatingAfter,
        String rulesetId,
        int rulesetVersion,
        int ratingPolicyVersion,
        int ratingKFactor,
        int teamSize,
        Instant startedAt,
        Instant finishedAt
) {
    public ClanWarHistoryEntry {
        warId = Objects.requireNonNull(warId, "warId");
        challengerClanId = Objects.requireNonNull(challengerClanId, "challengerClanId");
        defenderClanId = Objects.requireNonNull(defenderClanId, "defenderClanId");
        winningClanId = Objects.requireNonNull(winningClanId, "winningClanId");
        losingClanId = Objects.requireNonNull(losingClanId, "losingClanId");
        rulesetId = Objects.requireNonNull(rulesetId, "rulesetId");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
        if (challengerName == null || challengerName.isBlank() || challengerTag == null || challengerTag.isBlank()
                || defenderName == null || defenderName.isBlank() || defenderTag == null || defenderTag.isBlank()) {
            throw new IllegalArgumentException("clan names/tags must not be blank");
        }
        challengerName = challengerName.trim();
        challengerTag = challengerTag.trim();
        defenderName = defenderName.trim();
        defenderTag = defenderTag.trim();
        if (challengerClanId.equals(defenderClanId)
                || winningClanId.equals(losingClanId)
                || !winningClanId.equals(challengerClanId) && !winningClanId.equals(defenderClanId)
                || !losingClanId.equals(challengerClanId) && !losingClanId.equals(defenderClanId)) {
            throw new IllegalArgumentException("clan-war history participants are inconsistent");
        }
        if (challengerRatingBefore < 0 || challengerRatingAfter < 0 || defenderRatingBefore < 0 || defenderRatingAfter < 0
                || rulesetVersion < 1 || ratingPolicyVersion < 1 || ratingKFactor < 1 || teamSize < 1) {
            throw new IllegalArgumentException("clan-war history rating/version values are invalid");
        }
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not precede startedAt");
        }
    }
}
