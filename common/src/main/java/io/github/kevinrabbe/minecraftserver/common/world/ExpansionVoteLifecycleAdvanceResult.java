package io.github.kevinrabbe.minecraftserver.common.world;

/** One bounded expansion-vote lifecycle pass. */
public record ExpansionVoteLifecycleAdvanceResult(int opened, int resolved, int tied) {
    public ExpansionVoteLifecycleAdvanceResult {
        if (opened < 0 || resolved < 0 || tied < 0) {
            throw new IllegalArgumentException("lifecycle counts must be nonnegative");
        }
    }

    public int transitioned() {
        return opened + resolved;
    }
}
