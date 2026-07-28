package io.github.kevinrabbe.minecraftserver.common.pvp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClanWarSnapshot(
        UUID warId,
        UUID challengerClanId,
        UUID defenderClanId,
        ClanWarStatus status,
        UUID winningClanId,
        UUID settlementOperationId,
        UUID resolutionOperationId,
        UUID challengedByPlayerId,
        UUID acceptedByPlayerId,
        String rulesetId,
        int rulesetVersion,
        int ratingPolicyVersion,
        int ratingKFactor,
        int teamSize,
        long stateVersion,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public ClanWarSnapshot {
        warId = Objects.requireNonNull(warId, "warId");
        challengerClanId = Objects.requireNonNull(challengerClanId, "challengerClanId");
        defenderClanId = Objects.requireNonNull(defenderClanId, "defenderClanId");
        status = Objects.requireNonNull(status, "status");
        rulesetId = Objects.requireNonNull(rulesetId, "rulesetId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (challengerClanId.equals(defenderClanId)) {
            throw new IllegalArgumentException("clan war participants must be distinct");
        }
        if (rulesetVersion < 1 || ratingPolicyVersion < 1 || ratingKFactor <= 0 || teamSize <= 0 || stateVersion < 0) {
            throw new IllegalArgumentException("invalid clan war version/policy");
        }
    }
}
