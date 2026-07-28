package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable read projection of one authoritative completed 1.8.9 Ranked Arena match. */
public record RankedArenaHistoryEntry(
        UUID matchId,
        UUID playerAId,
        String playerAName,
        UUID playerBId,
        String playerBName,
        UUID winnerPlayerId,
        UUID loserPlayerId,
        int playerARatingBefore,
        int playerARatingAfter,
        int playerBRatingBefore,
        int playerBRatingAfter,
        String rulesetId,
        int rulesetVersion,
        int ratingPolicyVersion,
        int ratingKFactor,
        Instant startedAt,
        Instant finishedAt
) {
    public RankedArenaHistoryEntry {
        matchId = Objects.requireNonNull(matchId, "matchId");
        playerAId = Objects.requireNonNull(playerAId, "playerAId");
        playerBId = Objects.requireNonNull(playerBId, "playerBId");
        winnerPlayerId = Objects.requireNonNull(winnerPlayerId, "winnerPlayerId");
        loserPlayerId = Objects.requireNonNull(loserPlayerId, "loserPlayerId");
        rulesetId = Objects.requireNonNull(rulesetId, "rulesetId");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
        if (playerAName == null || playerAName.isBlank() || playerBName == null || playerBName.isBlank()) {
            throw new IllegalArgumentException("player names must not be blank");
        }
        playerAName = playerAName.trim();
        playerBName = playerBName.trim();
        if (playerAId.equals(playerBId)
                || winnerPlayerId.equals(loserPlayerId)
                || !winnerPlayerId.equals(playerAId) && !winnerPlayerId.equals(playerBId)
                || !loserPlayerId.equals(playerAId) && !loserPlayerId.equals(playerBId)) {
            throw new IllegalArgumentException("ranked history participants are inconsistent");
        }
        if (playerARatingBefore < 0 || playerARatingAfter < 0 || playerBRatingBefore < 0 || playerBRatingAfter < 0
                || rulesetVersion < 1 || ratingPolicyVersion < 1 || ratingKFactor < 1) {
            throw new IllegalArgumentException("ranked history rating/version values are invalid");
        }
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not precede startedAt");
        }
    }
}
