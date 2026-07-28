package io.github.kevinrabbe.minecraftserver.common.artifact;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ArtifactDefinitionSnapshot(
        UUID artifactId,
        int pointValue,
        int pointPolicyVersion,
        boolean enabled,
        ArtifactLocationSnapshot currentLocation,
        Instant createdAt,
        Instant updatedAt
) {
    public ArtifactDefinitionSnapshot {
        artifactId = Objects.requireNonNull(artifactId, "artifactId");
        currentLocation = Objects.requireNonNull(currentLocation, "currentLocation");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (pointValue <= 0 || pointPolicyVersion < 1) {
            throw new IllegalArgumentException("invalid artifact point policy");
        }
        if (!artifactId.equals(currentLocation.artifactId())) {
            throw new IllegalArgumentException("currentLocation belongs to another artifact");
        }
    }
}
