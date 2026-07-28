package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.util.Objects;
import java.util.UUID;

/** Immutable authoritative result of one completed ranked match. */
public record RankedMatchResult(
        RankedMatchSnapshot match,
        UUID loserPlayerId,
        RankedRatingSnapshot playerABefore,
        RankedRatingSnapshot playerAAfter,
        RankedRatingSnapshot playerBBefore,
        RankedRatingSnapshot playerBAfter,
        int ratingPolicyVersion,
        int ratingKFactor
) {
    public RankedMatchResult {
        match = Objects.requireNonNull(match, "match");
        loserPlayerId = Objects.requireNonNull(loserPlayerId, "loserPlayerId");
        playerABefore = Objects.requireNonNull(playerABefore, "playerABefore");
        playerAAfter = Objects.requireNonNull(playerAAfter, "playerAAfter");
        playerBBefore = Objects.requireNonNull(playerBBefore, "playerBBefore");
        playerBAfter = Objects.requireNonNull(playerBAfter, "playerBAfter");
        if (ratingPolicyVersion < 1 || ratingKFactor <= 0) {
            throw new IllegalArgumentException("invalid rating policy");
        }
    }
}
