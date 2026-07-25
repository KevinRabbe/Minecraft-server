package io.github.kevinrabbe.minecraftserver.common.artifact;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ArtifactLocationSnapshot(
        UUID artifactId,
        long locationRevision,
        String worldKey,
        String logicalZoneId,
        int blockX,
        int blockY,
        int blockZ,
        Instant createdAt
) {
    public ArtifactLocationSnapshot {
        artifactId = Objects.requireNonNull(artifactId, "artifactId");
        worldKey = Objects.requireNonNull(worldKey, "worldKey");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (locationRevision < 1) {
            throw new IllegalArgumentException("locationRevision must be >= 1");
        }
    }
}
