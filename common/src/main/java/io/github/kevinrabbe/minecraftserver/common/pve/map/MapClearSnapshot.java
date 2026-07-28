package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable leaderboard/history evidence for one successfully completed Map run. */
public record MapClearSnapshot(
        UUID clearId,
        UUID runId,
        MapDifficulty difficulty,
        long elapsedMillis,
        boolean solo,
        String worldEraId,
        int balanceVersion,
        Instant completedAt
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public MapClearSnapshot {
        clearId = Objects.requireNonNull(clearId, "clearId");
        runId = Objects.requireNonNull(runId, "runId");
        difficulty = Objects.requireNonNull(difficulty, "difficulty");
        if (elapsedMillis <= 0) {
            throw new IllegalArgumentException("elapsedMillis must be > 0");
        }
        if (worldEraId == null || worldEraId.isBlank()) {
            throw new IllegalArgumentException("worldEraId must not be blank");
        }
        worldEraId = worldEraId.trim();
        if (!ID.matcher(worldEraId).matches()) {
            throw new IllegalArgumentException("worldEraId has invalid format: " + worldEraId);
        }
        if (balanceVersion < 0) {
            throw new IllegalArgumentException("balanceVersion must be >= 0");
        }
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }
}
