package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable player-facing Ranked ladder row derived from authoritative match result evidence. */
public record RankedLeaderboardEntry(
        int position,
        UUID playerId,
        String playerName,
        int rating,
        int peakRating,
        long wins,
        long losses,
        Instant lastResultAt,
        String rulesetId,
        int rulesetVersion,
        int ratingPolicyVersion
) {
    public RankedLeaderboardEntry {
        if (position < 1) throw new IllegalArgumentException("position must be >= 1");
        playerId = Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName").trim();
        if (playerName.isEmpty() || playerName.length() > 16) {
            throw new IllegalArgumentException("playerName must contain 1-16 characters");
        }
        if (rating < 0 || peakRating < rating) {
            throw new IllegalArgumentException("rating must be >= 0 and peakRating must be >= rating");
        }
        if (wins < 0 || losses < 0 || wins + losses < 1) {
            throw new IllegalArgumentException("leaderboard entry requires at least one completed match");
        }
        lastResultAt = Objects.requireNonNull(lastResultAt, "lastResultAt");
        rulesetId = Objects.requireNonNull(rulesetId, "rulesetId").trim();
        if (rulesetId.isEmpty() || rulesetVersion < 1 || ratingPolicyVersion < 1) {
            throw new IllegalArgumentException("ruleset identity must be complete");
        }
    }

    public long matchesPlayed() {
        return wins + losses;
    }
}
