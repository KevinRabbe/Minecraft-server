package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.util.Objects;
import java.util.UUID;

/** Persisted route back from one disposable Map encounter to the logical zone where its source Map was opened. */
public record MapEncounterReturnRoute(
        UUID runId,
        UUID playerId,
        String sourceZoneId,
        String targetBackendId,
        MapRunStatus runStatus
) {
    public MapEncounterReturnRoute {
        runId = Objects.requireNonNull(runId, "runId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        sourceZoneId = requireText(sourceZoneId, "sourceZoneId");
        targetBackendId = requireText(targetBackendId, "targetBackendId");
        runStatus = Objects.requireNonNull(runStatus, "runStatus");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
