package io.github.kevinrabbe.minecraftserver.common.artifact;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Startup compatibility gate for persistently enabled hidden Artifact definitions. */
public final class ArtifactLivePlacementCompatibilityValidator {
    private ArtifactLivePlacementCompatibilityValidator() { }

    /**
     * Requires every persistently enabled Artifact ID to remain present in the loaded physical-placement catalog.
     * Disabled definitions are historical authority and may be omitted. Additional configured IDs remain valid because
     * the later bootstrap step creates them idempotently.
     */
    public static void validate(DataSource dataSource, Set<UUID> configuredArtifactIds) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(configuredArtifactIds, "configuredArtifactIds");
        Set<UUID> configured = Set.copyOf(configuredArtifactIds);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT artifact_id
                     FROM artifact_definitions
                     WHERE enabled
                     ORDER BY artifact_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID artifactId = rows.getObject("artifact_id", UUID.class);
                if (!configured.contains(artifactId)) {
                    throw new ArtifactException(
                            "Persistently enabled Artifact " + artifactId
                                    + " is absent from the loaded physical placement catalog"
                    );
                }
            }
        }
    }
}
