package io.github.kevinrabbe.minecraftserver.common.world;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable candidate-set snapshot for one authoritative expansion vote. */
public record ExpansionVoteDefinition(
        UUID voteId,
        int candidateSetVersion,
        Instant opensAt,
        Instant closesAt,
        List<ExpansionCandidate> candidates
) {
    public ExpansionVoteDefinition {
        voteId = Objects.requireNonNull(voteId, "voteId");
        if (candidateSetVersion < 0) {
            throw new IllegalArgumentException("candidateSetVersion must be >= 0");
        }
        opensAt = Objects.requireNonNull(opensAt, "opensAt");
        closesAt = Objects.requireNonNull(closesAt, "closesAt");
        if (!closesAt.isAfter(opensAt)) {
            throw new IllegalArgumentException("closesAt must be after opensAt");
        }
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.size() < 2) {
            throw new IllegalArgumentException("an expansion vote requires at least two candidates");
        }
        LinkedHashSet<String> candidateIds = new LinkedHashSet<>();
        for (ExpansionCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (!candidateIds.add(candidate.candidateId())) {
                throw new IllegalArgumentException("duplicate candidateId: " + candidate.candidateId());
            }
        }
        candidates = List.copyOf(candidates);
    }
}
