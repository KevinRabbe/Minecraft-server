package io.github.kevinrabbe.minecraftserver.common.world.resource;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current authoritative renewable source head for one live zone instance. */
public record ResourceSourceSnapshot(
        UUID sourceId,
        UUID instanceId,
        String sourceKey,
        String definitionId,
        long cycleNo,
        Instant nextAvailableAt,
        long stateVersion
) {
    public ResourceSourceSnapshot {
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        if (sourceKey == null || sourceKey.isBlank() || definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("sourceKey/definitionId must not be blank");
        }
        if (cycleNo < 0 || stateVersion < 0) {
            throw new IllegalArgumentException("cycleNo/stateVersion must be nonnegative");
        }
        nextAvailableAt = Objects.requireNonNull(nextAvailableAt, "nextAvailableAt");
    }
}
