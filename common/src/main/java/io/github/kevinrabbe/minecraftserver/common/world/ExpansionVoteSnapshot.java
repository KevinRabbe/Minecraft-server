package io.github.kevinrabbe.minecraftserver.common.world;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ExpansionVoteSnapshot(
        UUID voteId,
        int candidateSetVersion,
        ExpansionVoteStatus status,
        Instant opensAt,
        Instant closesAt,
        String winningCandidateId,
        UUID resolutionOperationId,
        Instant resolvedAt
) {
    public ExpansionVoteSnapshot {
        voteId = Objects.requireNonNull(voteId, "voteId");
        status = Objects.requireNonNull(status, "status");
        opensAt = Objects.requireNonNull(opensAt, "opensAt");
        closesAt = Objects.requireNonNull(closesAt, "closesAt");
        if (candidateSetVersion < 0 || !closesAt.isAfter(opensAt)) {
            throw new IllegalArgumentException("invalid expansion vote snapshot");
        }
        if (status == ExpansionVoteStatus.RESOLVED
                && (winningCandidateId == null || resolutionOperationId == null || resolvedAt == null)) {
            throw new IllegalArgumentException("RESOLVED vote requires winner/resolution metadata");
        }
        if (status != ExpansionVoteStatus.RESOLVED
                && (winningCandidateId != null || resolutionOperationId != null || resolvedAt != null)) {
            throw new IllegalArgumentException("non-resolved vote must not carry resolution metadata");
        }
    }
}
