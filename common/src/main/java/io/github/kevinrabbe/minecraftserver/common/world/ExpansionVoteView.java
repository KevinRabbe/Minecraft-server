package io.github.kevinrabbe.minecraftserver.common.world;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded read projection of one currently castable expansion vote plus this player's effective ballot. */
public record ExpansionVoteView(
        ExpansionVoteSnapshot vote,
        List<ExpansionCandidate> candidates,
        ExpansionBallot ballot
) {
    public ExpansionVoteView {
        vote = Objects.requireNonNull(vote, "vote");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (vote.status() != ExpansionVoteStatus.OPEN) {
            throw new IllegalArgumentException("ExpansionVoteView requires an OPEN vote");
        }
        if (candidates.size() < 2) {
            throw new IllegalArgumentException("ExpansionVoteView requires at least two candidates");
        }
        HashSet<String> candidateIds = new HashSet<>();
        for (ExpansionCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (!candidateIds.add(candidate.candidateId())) {
                throw new IllegalArgumentException("duplicate candidateId in expansion vote view: " + candidate.candidateId());
            }
        }
        if (ballot != null) {
            if (!ballot.voteId().equals(vote.voteId())
                    || ballot.candidateSetVersion() != vote.candidateSetVersion()
                    || !candidateIds.contains(ballot.candidateId())) {
                throw new IllegalArgumentException("ballot does not belong to this expansion vote view");
            }
        }
    }
}
