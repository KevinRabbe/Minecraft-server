package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementProfileCatalog;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only bounded reconciliation of Artifact source-operation evidence and current Attunement profile identity. */
public final class ArtifactIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String CREATE_OPERATION = "ARTIFACT_CREATE";
    private static final String RELOCATE_OPERATION = "ARTIFACT_RELOCATE";
    private static final String DISCOVER_OPERATION = "ARTIFACT_DISCOVER";

    private final DataSource dataSource;
    private final Optional<AttunementProfileCatalog> profileCatalog;

    public ArtifactIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.profileCatalog = Optional.empty();
    }

    public ArtifactIntegrityVerifier(DataSource dataSource, AttunementProfileCatalog profileCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.profileCatalog = Optional.of(Objects.requireNonNull(profileCatalog, "profileCatalog"));
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyCreationEvidence(connection, issues, maxIssues);
            verifyRelocationEvidence(connection, issues, maxIssues);
            verifyDiscoveryEvidence(connection, issues, maxIssues);
            if (profileCatalog.isPresent()) {
                verifyActiveProfiles(connection, profileCatalog.orElseThrow(), issues, maxIssues);
            }
            return List.copyOf(issues);
        }
    }

    private static void verifyCreationEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT definition.artifact_id,
                       definition.definition_operation_id,
                       initial.operation_id AS initial_location_operation_id,
                       operation.operation_type
                FROM artifact_definitions definition
                LEFT JOIN artifact_locations initial
                  ON initial.artifact_id = definition.artifact_id
                 AND initial.location_revision = 1
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = definition.definition_operation_id
                WHERE initial.artifact_id IS NULL
                   OR initial.operation_id IS DISTINCT FROM definition.definition_operation_id
                   OR operation.operation_id IS NULL
                   OR operation.operation_type IS DISTINCT FROM ?
                   OR operation.result -> 'request' ->> 'artifact_id'
                        IS DISTINCT FROM definition.artifact_id::TEXT
                   OR operation.result -> 'request' ->> 'world_key'
                        IS DISTINCT FROM initial.world_key
                   OR operation.result -> 'request' ->> 'logical_zone_id'
                        IS DISTINCT FROM initial.logical_zone_id
                   OR operation.result -> 'request' ->> 'block_x'
                        IS DISTINCT FROM initial.block_x::TEXT
                   OR operation.result -> 'request' ->> 'block_y'
                        IS DISTINCT FROM initial.block_y::TEXT
                   OR operation.result -> 'request' ->> 'block_z'
                        IS DISTINCT FROM initial.block_z::TEXT
                ORDER BY definition.artifact_id
                LIMIT ?
                """)) {
            statement.setString(1, CREATE_OPERATION);
            statement.setInt(2, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID artifactId = rows.getObject("artifact_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "ARTIFACT_CREATE_EVIDENCE_MISMATCH",
                            artifactId.toString(),
                            "Artifact definition/initial location does not reconcile with source create operation "
                                    + rows.getObject("definition_operation_id", UUID.class)
                    ));
                }
            }
        }
    }

    private static void verifyRelocationEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT location.artifact_id,
                       location.location_revision,
                       location.operation_id
                FROM artifact_locations location
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = location.operation_id
                WHERE location.location_revision > 1
                  AND (
                       operation.operation_id IS NULL
                    OR operation.operation_type IS DISTINCT FROM ?
                    OR operation.result -> 'request' ->> 'artifact_id'
                         IS DISTINCT FROM location.artifact_id::TEXT
                    OR operation.result -> 'request' ->> 'expected_location_revision'
                         IS DISTINCT FROM (location.location_revision - 1)::TEXT
                    OR operation.result -> 'request' ->> 'world_key'
                         IS DISTINCT FROM location.world_key
                    OR operation.result -> 'request' ->> 'logical_zone_id'
                         IS DISTINCT FROM location.logical_zone_id
                    OR operation.result -> 'request' ->> 'block_x'
                         IS DISTINCT FROM location.block_x::TEXT
                    OR operation.result -> 'request' ->> 'block_y'
                         IS DISTINCT FROM location.block_y::TEXT
                    OR operation.result -> 'request' ->> 'block_z'
                         IS DISTINCT FROM location.block_z::TEXT
                  )
                ORDER BY location.artifact_id, location.location_revision
                LIMIT ?
                """)) {
            statement.setString(1, RELOCATE_OPERATION);
            statement.setInt(2, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID artifactId = rows.getObject("artifact_id", UUID.class);
                    long revision = rows.getLong("location_revision");
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "ARTIFACT_RELOCATION_EVIDENCE_MISMATCH",
                            artifactId + "/" + revision,
                            "Artifact location revision does not reconcile with source relocation operation "
                                    + rows.getObject("operation_id", UUID.class)
                    ));
                }
            }
        }
    }

    private static void verifyDiscoveryEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT discovery.player_id,
                       discovery.artifact_id,
                       discovery.operation_id
                FROM player_artifact_discoveries discovery
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = discovery.operation_id
                WHERE operation.operation_id IS NULL
                   OR operation.operation_type IS DISTINCT FROM ?
                   OR operation.result -> 'request' ->> 'player_id'
                        IS DISTINCT FROM discovery.player_id::TEXT
                   OR operation.result -> 'request' ->> 'artifact_id'
                        IS DISTINCT FROM discovery.artifact_id::TEXT
                   OR operation.result -> 'request' ->> 'expected_location_revision'
                        IS DISTINCT FROM discovery.location_revision::TEXT
                   OR operation.result -> 'request' ->> 'world_era_context'
                        IS DISTINCT FROM discovery.world_era_context
                   OR operation.result -> 'result' ->> 'newly_discovered'
                        IS DISTINCT FROM 'true'
                   OR operation.result -> 'result' -> 'discovery' ->> 'player_id'
                        IS DISTINCT FROM discovery.player_id::TEXT
                   OR operation.result -> 'result' -> 'discovery' ->> 'artifact_id'
                        IS DISTINCT FROM discovery.artifact_id::TEXT
                   OR operation.result -> 'result' -> 'discovery' ->> 'location_revision'
                        IS DISTINCT FROM discovery.location_revision::TEXT
                   OR operation.result -> 'result' -> 'discovery' ->> 'points_awarded'
                        IS DISTINCT FROM discovery.points_awarded::TEXT
                   OR operation.result -> 'result' -> 'discovery' ->> 'point_policy_version'
                        IS DISTINCT FROM discovery.point_policy_version::TEXT
                   OR operation.result -> 'result' -> 'discovery' ->> 'world_era_context'
                        IS DISTINCT FROM discovery.world_era_context
                ORDER BY discovery.player_id, discovery.artifact_id
                LIMIT ?
                """)) {
            statement.setString(1, DISCOVER_OPERATION);
            statement.setInt(2, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    UUID artifactId = rows.getObject("artifact_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "ARTIFACT_DISCOVERY_EVIDENCE_MISMATCH",
                            playerId + "/" + artifactId,
                            "Immutable Artifact discovery does not reconcile with source operation "
                                    + rows.getObject("operation_id", UUID.class)
                    ));
                }
            }
        }
    }

    private static void verifyActiveProfiles(
            Connection connection,
            AttunementProfileCatalog catalog,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        String[] knownProfileIds = catalog.all().stream()
                .map(profile -> profile.profileId())
                .toArray(String[]::new);
        Array known = connection.createArrayOf("text", knownProfileIds);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH known(profile_id) AS (
                        SELECT UNNEST(?::TEXT[])
                    )
                    SELECT state.player_id, state.active_profile_id
                    FROM player_attunement_state state
                    WHERE state.active_profile_id IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM known WHERE known.profile_id = state.active_profile_id
                      )
                    ORDER BY state.player_id
                    LIMIT ?
                    """)) {
                statement.setArray(1, known);
                statement.setInt(2, remaining);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        UUID playerId = rows.getObject("player_id", UUID.class);
                        String profileId = rows.getString("active_profile_id");
                        issues.add(new IntegrityIssue(
                                IntegritySeverity.CRITICAL,
                                "ATTUNEMENT_PROFILE_UNKNOWN",
                                playerId.toString(),
                                "Current Attunement state references unknown profile " + profileId
                        ));
                    }
                }
            }
        } finally {
            known.free();
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
