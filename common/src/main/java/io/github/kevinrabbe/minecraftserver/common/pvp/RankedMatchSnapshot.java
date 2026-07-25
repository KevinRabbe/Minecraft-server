package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RankedMatchSnapshot(
        UUID matchId,
        UUID playerAId,
        UUID playerBId,
        RankedMatchStatus status,
        UUID winnerPlayerId,
        UUID resultOperationId,
        String rulesetId,
        int rulesetVersion,
        int ratingPolicyVersion,
        int ratingKFactor,
        long stateVersion,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public RankedMatchSnapshot {
        matchId = Objects.requireNonNull(matchId, "matchId");
        playerAId = Objects.requireNonNull(playerAId, "playerAId");
        playerBId = Objects.requireNonNull(playerBId, "playerBId");
        status = Objects.requireNonNull(status, "status");
        rulesetId = Objects.requireNonNull(rulesetId, "rulesetId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (playerAId.equals(playerBId)) {
            throw new IllegalArgumentException("ranked match players must be distinct");
        }
        if (rulesetVersion < 1 || ratingPolicyVersion < 1 || ratingKFactor <= 0 || stateVersion < 0) {
            throw new IllegalArgumentException("invalid ranked match version/policy");
        }
    }
}
