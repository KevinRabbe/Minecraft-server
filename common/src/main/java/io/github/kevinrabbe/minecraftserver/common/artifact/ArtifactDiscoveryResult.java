package io.github.kevinrabbe.minecraftserver.common.artifact;

import java.util.Objects;

/** Result of a discovery attempt. Existing discoveries replay without awarding points twice. */
public record ArtifactDiscoveryResult(
        ArtifactDiscoverySnapshot discovery,
        boolean newlyDiscovered,
        long totalAttunementPoints
) {
    public ArtifactDiscoveryResult {
        discovery = Objects.requireNonNull(discovery, "discovery");
        if (totalAttunementPoints < discovery.pointsAwarded()) {
            throw new IllegalArgumentException("totalAttunementPoints cannot be below this discovery's points");
        }
    }
}
