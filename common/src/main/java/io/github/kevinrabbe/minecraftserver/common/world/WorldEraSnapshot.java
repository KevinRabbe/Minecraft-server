package io.github.kevinrabbe.minecraftserver.common.world;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable read projection of one canonical world era. */
public record WorldEraSnapshot(
        WorldEraId eraId,
        int sequenceNumber,
        UUID sourceOperationId,
        Instant startedAt
) {
    public WorldEraSnapshot {
        eraId = Objects.requireNonNull(eraId, "eraId");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 0");
        }
    }
}
