package io.github.kevinrabbe.minecraftserver.common.world;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Advances due expansion votes while preserving developer-neutral tie behavior. */
public final class ExpansionVoteLifecycleService {
    private static final String OPEN_REASON = "vote.lifecycle_open";
    private static final String RESOLVE_REASON = "vote.lifecycle_resolve";

    private final ExpansionVoteLifecycleQueryRepository queries;
    private final ExpansionVoteRepository votes;
    private final int batchLimit;

    public ExpansionVoteLifecycleService(
            ExpansionVoteLifecycleQueryRepository queries,
            ExpansionVoteRepository votes,
            int batchLimit
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.votes = Objects.requireNonNull(votes, "votes");
        if (batchLimit < 1 || batchLimit > 100) {
            throw new IllegalArgumentException("batchLimit must be between 1 and 100");
        }
        this.batchLimit = batchLimit;
    }

    /**
     * Opens due scheduled votes and resolves due open votes. Ties deliberately remain unresolved for an explicit runoff.
     * Deterministic operation IDs make concurrent/retried lifecycle passes converge on the same authoritative action.
     */
    public ExpansionVoteLifecycleAdvanceResult advanceOnce() throws SQLException {
        int opened = 0;
        for (UUID voteId : queries.listOpenable(batchLimit)) {
            votes.open(operationId("open", voteId), voteId, OPEN_REASON);
            opened++;
        }

        int resolved = 0;
        int tied = 0;
        for (UUID voteId : queries.listResolvable(batchLimit)) {
            try {
                votes.resolve(operationId("resolve", voteId), voteId, RESOLVE_REASON);
                resolved++;
            } catch (ExpansionVoteTieException ignored) {
                tied++;
            }
        }
        return new ExpansionVoteLifecycleAdvanceResult(opened, resolved, tied);
    }

    private static UUID operationId(String action, UUID voteId) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:expansion-vote:" + action + ":" + voteId).getBytes(StandardCharsets.UTF_8)
        );
    }
}
