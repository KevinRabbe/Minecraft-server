package io.github.kevinrabbe.minecraftserver.common.world.resource;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persistent binding between one resource-source cycle and one concrete runtime entity identity. */
public record ResourceEntitySpawnSnapshot(
        UUID spawnId,
        UUID sourceId,
        long sourceCycleNo,
        ResourceEntitySpawnStatus status,
        UUID entityUuid,
        Instant leaseExpiresAt,
        UUID killerPlayerId,
        Instant createdAt,
        Instant confirmedAt,
        Instant resolvedAt
) {
    public ResourceEntitySpawnSnapshot {
        spawnId = Objects.requireNonNull(spawnId, "spawnId");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        status = Objects.requireNonNull(status, "status");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (sourceCycleNo < 0) {
            throw new IllegalArgumentException("sourceCycleNo must be >= 0");
        }
    }
}
