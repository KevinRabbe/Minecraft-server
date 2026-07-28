package io.github.kevinrabbe.minecraftserver.common.world.resource;

import java.util.Objects;
import java.util.UUID;

/** Immutable authorization binding one entity death to the existing resource-harvest operation. */
public record ResourceEntityKillClaim(
        UUID operationId,
        UUID spawnId,
        UUID sourceId,
        long sourceCycleNo,
        UUID entityUuid
) {
    public ResourceEntityKillClaim {
        operationId = Objects.requireNonNull(operationId, "operationId");
        spawnId = Objects.requireNonNull(spawnId, "spawnId");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        entityUuid = Objects.requireNonNull(entityUuid, "entityUuid");
        if (sourceCycleNo < 0) {
            throw new IllegalArgumentException("sourceCycleNo must be >= 0");
        }
    }
}
