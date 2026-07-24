package io.github.kevinrabbe.minecraftserver.common.world;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable authoritative resolution result for one expansion vote. */
public record ExpansionVoteResult(
        UUID voteId,
        int candidateSetVersion,
        String winningCandidateId,
        Map<String, Long> ballotCounts,
        Instant resolvedAt
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public ExpansionVoteResult {
        voteId = Objects.requireNonNull(voteId, "voteId");
        if (candidateSetVersion < 0) {
            throw new IllegalArgumentException("candidateSetVersion must be >= 0");
        }
        winningCandidateId = requireId(winningCandidateId, "winningCandidateId");
        Objects.requireNonNull(ballotCounts, "ballotCounts");
        if (ballotCounts.isEmpty()) {
            throw new IllegalArgumentException("ballotCounts must not be empty");
        }
        TreeMap<String, Long> normalized = new TreeMap<>();
        ballotCounts.forEach((candidateId, count) -> {
            String id = requireId(candidateId, "candidateId");
            if (count == null || count < 0) {
                throw new IllegalArgumentException("ballot count must be >= 0 for " + id);
            }
            normalized.put(id, count);
        });
        if (!normalized.containsKey(winningCandidateId)) {
            throw new IllegalArgumentException("winningCandidateId is not present in ballotCounts");
        }
        ballotCounts = Map.copyOf(normalized);
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    }

    public long totalBallots() {
        return ballotCounts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static String requireId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " has invalid format: " + normalized);
        }
        return normalized;
    }
}
