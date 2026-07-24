package io.github.kevinrabbe.minecraftserver.common.world;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** One authoritative player's ballot against one immutable candidate-set version. */
public record ExpansionBallot(
        UUID voteId,
        UUID playerId,
        int candidateSetVersion,
        String candidateId,
        Instant castAt
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public ExpansionBallot {
        voteId = Objects.requireNonNull(voteId, "voteId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (candidateSetVersion < 0) {
            throw new IllegalArgumentException("candidateSetVersion must be >= 0");
        }
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        candidateId = candidateId.trim();
        if (!ID.matcher(candidateId).matches()) {
            throw new IllegalArgumentException("candidateId has invalid format: " + candidateId);
        }
        castAt = Objects.requireNonNull(castAt, "castAt");
    }
}
