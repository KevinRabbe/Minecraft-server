package io.github.kevinrabbe.minecraftserver.common.artifact;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable per-player evidence that one stable artifact identity was discovered. */
public record ArtifactDiscoverySnapshot(
        UUID playerId,
        UUID artifactId,
        long locationRevision,
        int pointsAwarded,
        int pointPolicyVersion,
        String worldEraContext,
        Instant discoveredAt
) {
    public ArtifactDiscoverySnapshot {
        playerId = Objects.requireNonNull(playerId, "playerId");
        artifactId = Objects.requireNonNull(artifactId, "artifactId");
        discoveredAt = Objects.requireNonNull(discoveredAt, "discoveredAt");
        if (locationRevision < 1 || pointsAwarded <= 0 || pointPolicyVersion < 1) {
            throw new IllegalArgumentException("invalid artifact discovery evidence");
        }
    }
}
