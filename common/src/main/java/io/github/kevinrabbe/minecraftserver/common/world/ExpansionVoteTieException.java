package io.github.kevinrabbe.minecraftserver.common.world;

import java.util.List;

/** A tied vote is not silently broken by developer-authored ordering; it requires an explicit runoff/new vote. */
public final class ExpansionVoteTieException extends ExpansionVoteException {
    private final List<String> tiedCandidateIds;
    private final long tiedBallots;

    public ExpansionVoteTieException(List<String> tiedCandidateIds, long tiedBallots) {
        super("Expansion vote is tied and requires a runoff: " + tiedCandidateIds);
        this.tiedCandidateIds = List.copyOf(tiedCandidateIds);
        this.tiedBallots = tiedBallots;
    }

    public List<String> tiedCandidateIds() {
        return tiedCandidateIds;
    }

    public long tiedBallots() {
        return tiedBallots;
    }
}
