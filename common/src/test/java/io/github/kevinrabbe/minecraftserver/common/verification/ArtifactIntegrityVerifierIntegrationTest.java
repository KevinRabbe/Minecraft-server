package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.artifact.ArtifactRepository;
import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementProfileCatalog;
import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementProfileDefinition;
import io.github.kevinrabbe.minecraftserver.common.artifact.AttunementRepository;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ArtifactIntegrityVerifierIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ArtifactRepository artifacts;
    private AttunementProfileCatalog profiles;
    private AttunementRepository attunement;
    private ArtifactIntegrityVerifier verifier;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                6
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        artifacts = new ArtifactRepository(dataSource);
        profiles = new AttunementProfileCatalog(List.of(
                new AttunementProfileDefinition("arcane", "intelligence"),
                new AttunementProfileDefinition("martial", "strength")
        ));
        attunement = new AttunementRepository(dataSource, profiles);
        verifier = new ArtifactIntegrityVerifier(dataSource, profiles);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        player_attunement_state,
                        player_artifact_discoveries,
                        artifact_locations,
                        artifact_definitions,
                        processed_operations,
                        player_state,
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void repositoryCreatedArtifactHistoryAndKnownAttunementAreClean() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "ArtifactIntegrity");
        UUID artifactId = UUID.randomUUID();
        artifacts.createArtifact(
                UUID.randomUUID(), artifactId, 3, 1, true,
                "minecraft:overworld", "city", 10, 64, 10
        );
        artifacts.relocate(
                UUID.randomUUID(), artifactId, 1,
                "minecraft:overworld", "district.arcane", 90, 70, -20
        );
        artifacts.discover(UUID.randomUUID(), playerId, artifactId, 2, "era-1");
        attunement.setActiveProfile(UUID.randomUUID(), playerId, "arcane");

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void missingCreateOperationEvidenceIsDetected() throws Exception {
        UUID artifactId = UUID.randomUUID();
        UUID missingOperationId = UUID.randomUUID();
        insertArtifactDefinition(artifactId, missingOperationId, 2, 1, true);
        insertArtifactLocation(
                artifactId, 1, missingOperationId,
                "minecraft:overworld", "city", 1, 65, 1
        );

        assertSingleIssue("ARTIFACT_CREATE_EVIDENCE_MISMATCH", artifactId.toString());
    }

    @Test
    void missingRelocationOperationEvidenceIsDetected() throws Exception {
        UUID artifactId = UUID.randomUUID();
        artifacts.createArtifact(
                UUID.randomUUID(), artifactId, 2, 1, true,
                "minecraft:overworld", "city", 1, 65, 1
        );
        insertArtifactLocation(
                artifactId, 2, UUID.randomUUID(),
                "minecraft:overworld", "district.new", 100, 70, 100
        );

        assertSingleIssue("ARTIFACT_RELOCATION_EVIDENCE_MISMATCH", artifactId + "/2");
    }

    @Test
    void missingDiscoveryOperationEvidenceIsDetected() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "ArtifactEvidence");
        UUID artifactId = UUID.randomUUID();
        artifacts.createArtifact(
                UUID.randomUUID(), artifactId, 4, 1, true,
                "minecraft:overworld", "city", 2, 65, 2
        );
        insertDiscovery(playerId, artifactId, UUID.randomUUID(), 1, 4, 1, "era-1");

        assertSingleIssue(
                "ARTIFACT_DISCOVERY_EVIDENCE_MISMATCH",
                playerId + "/" + artifactId
        );
    }

    @Test
    void activeAttunementProfileMustRemainInLoadedCatalog() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "AttuneEvidence");
        insertAttunementState(playerId, "retired_profile", 1);

        assertSingleIssue("ATTUNEMENT_PROFILE_UNKNOWN", playerId.toString());
    }

    private void assertSingleIssue(String expectedCode, String expectedSubject) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals(expectedCode, issue.code());
        assertEquals(expectedSubject, issue.subjectId());
    }

    private void insertArtifactDefinition(
            UUID artifactId,
            UUID operationId,
            int pointValue,
            int policyVersion,
            boolean enabled
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO artifact_definitions(
                         artifact_id, definition_operation_id, point_value, point_policy_version, enabled
                     ) VALUES (?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, artifactId);
            statement.setObject(2, operationId);
            statement.setInt(3, pointValue);
            statement.setInt(4, policyVersion);
            statement.setBoolean(5, enabled);
            statement.executeUpdate();
        }
    }

    private void insertArtifactLocation(
            UUID artifactId,
            long revision,
            UUID operationId,
            String worldKey,
            String zoneId,
            int blockX,
            int blockY,
            int blockZ
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO artifact_locations(
                         artifact_id, location_revision, operation_id, world_key,
                         logical_zone_id, block_x, block_y, block_z
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, artifactId);
            statement.setLong(2, revision);
            statement.setObject(3, operationId);
            statement.setString(4, worldKey);
            statement.setString(5, zoneId);
            statement.setInt(6, blockX);
            statement.setInt(7, blockY);
            statement.setInt(8, blockZ);
            statement.executeUpdate();
        }
    }

    private void insertDiscovery(
            UUID playerId,
            UUID artifactId,
            UUID operationId,
            long locationRevision,
            int points,
            int policyVersion,
            String worldEraContext
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO player_artifact_discoveries(
                         player_id, artifact_id, operation_id, location_revision,
                         points_awarded, point_policy_version, world_era_context
                     ) VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, playerId);
            statement.setObject(2, artifactId);
            statement.setObject(3, operationId);
            statement.setLong(4, locationRevision);
            statement.setInt(5, points);
            statement.setInt(6, policyVersion);
            statement.setString(7, worldEraContext);
            statement.executeUpdate();
        }
    }

    private void insertAttunementState(UUID playerId, String profileId, long version) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO player_attunement_state(player_id, active_profile_id, state_version)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, profileId);
            statement.setLong(3, version);
            statement.executeUpdate();
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }
}
