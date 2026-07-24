package io.github.kevinrabbe.minecraftserver.common.pve.map;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistent lifecycle snapshot for one execution of one opened Map item. */
public record MapRunSnapshot(
        UUID runId,
        UUID sourceMapItemId,
        MapRunStatus status,
        MapRunDefinition definition,
        long stateVersion,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public MapRunSnapshot {
        runId = Objects.requireNonNull(runId, "runId");
        sourceMapItemId = Objects.requireNonNull(sourceMapItemId, "sourceMapItemId");
        status = Objects.requireNonNull(status, "status");
        definition = Objects.requireNonNull(definition, "definition");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        if (startedAt != null && startedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("startedAt must not be before createdAt");
        }
        if (finishedAt != null) {
            Instant lowerBound = startedAt == null ? createdAt : startedAt;
            if (finishedAt.isBefore(lowerBound)) {
                throw new IllegalArgumentException("finishedAt must not precede run start");
            }
        }
        if ((status == MapRunStatus.COMPLETED || status == MapRunStatus.FAILED || status == MapRunStatus.CLOSED)
                && finishedAt == null) {
            throw new IllegalArgumentException("terminal Map run status requires finishedAt");
        }
    }
}
