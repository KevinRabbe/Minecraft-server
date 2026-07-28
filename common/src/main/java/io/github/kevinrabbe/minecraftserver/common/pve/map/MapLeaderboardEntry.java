package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Historically interpretable Persistent-MMO Map leaderboard row. */
public record MapLeaderboardEntry(
        int rank,
        UUID clearId,
        UUID runId,
        MapDifficulty difficulty,
        long elapsedMillis,
        boolean solo,
        String environmentId,
        String enemyFamilyId,
        String objectiveId,
        String modifierJson,
        String worldEraId,
        int balanceVersion,
        Instant completedAt,
        List<MapLeaderboardParticipant> participants
) {
    public MapLeaderboardEntry {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be >= 1");
        }
        clearId = Objects.requireNonNull(clearId, "clearId");
        runId = Objects.requireNonNull(runId, "runId");
        difficulty = Objects.requireNonNull(difficulty, "difficulty");
        if (elapsedMillis <= 0) {
            throw new IllegalArgumentException("elapsedMillis must be > 0");
        }
        environmentId = requireText(environmentId, "environmentId");
        enemyFamilyId = requireText(enemyFamilyId, "enemyFamilyId");
        objectiveId = requireText(objectiveId, "objectiveId");
        modifierJson = requireText(modifierJson, "modifierJson");
        worldEraId = requireText(worldEraId, "worldEraId");
        if (balanceVersion < 0) {
            throw new IllegalArgumentException("balanceVersion must be >= 0");
        }
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("participants must not be empty");
        }
        if (solo != (participants.size() == 1)) {
            throw new IllegalArgumentException("solo flag must match participant count");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
