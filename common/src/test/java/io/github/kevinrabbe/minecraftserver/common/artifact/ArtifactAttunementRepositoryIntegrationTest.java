package io.github.kevinrabbe.minecraftserver.common.artifact;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ArtifactAttunementRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ArtifactRepository artifacts;
    private AttunementRepository attunement;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                10
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        artifacts = new ArtifactRepository(dataSource);
        attunement = new AttunementRepository(
                dataSource,
                new AttunementProfileCatalog(List.of(
                        new AttunementProfileDefinition("arcane", "intelligence"),
                        new AttunementProfileDefinition("martial", "strength")
                ))
        );
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
    void artifactCreationAndRelocationAreExactAndPreserveStableIdentity() throws Exception {
        UUID artifactId = UUID.randomUUID();
        UUID createOperation = UUID.randomUUID();
        ArtifactDefinitionSnapshot created = artifacts.createArtifact(
                createOperation, artifactId, 2, 1, true,
                "minecraft:overworld", "city", 10, 64, -3
        );
        ArtifactDefinitionSnapshot retry = artifacts.createArtifact(
                createOperation, artifactId, 2, 1, true,
                "minecraft:overworld", "city", 10, 64, -3
        );

        assertEquals(created, retry);
        assertEquals(artifactId, created.artifactId());
        assertEquals(1L, created.currentLocation().locationRevision());
        assertThrows(
                ArtifactException.class,
                () -> artifacts.createArtifact(
                        createOperation, artifactId, 3, 1, true,
                        "minecraft:overworld", "city", 10, 64, -3
                )
        );

        UUID relocateOperation = UUID.randomUUID();
        ArtifactDefinitionSnapshot relocated = artifacts.relocate(
                relocateOperation, artifactId, 1,
                "minecraft:overworld", "district.arcane", 200, 72, 90
        );
        assertEquals(relocated, artifacts.relocate(
                relocateOperation, artifactId, 1,
                "minecraft:overworld", "district.arcane", 200, 72, 90
        ));
        assertEquals(artifactId, relocated.artifactId());
        assertEquals(2L, relocated.currentLocation().locationRevision());
        assertEquals(2L, artifactLocationCount(artifactId));
        assertThrows(
                ArtifactException.class,
                () -> artifacts.relocate(
                        UUID.randomUUID(), artifactId, 1,
                        "minecraft:overworld", "district.arcane", 201, 72, 90
                )
        );
        assertThrows(SQLException.class, () -> mutateHistoricalLocation(artifactId, 1));
        assertThrows(SQLException.class, () -> insertSkippedLocationRevision(artifactId, 4));
    }

    @Test
    void discoveryAwardsOnceAndRepeatedInteractionNeverDuplicatesPoints() throws Exception {
        UUID player = player("ArtifactOnce");
        UUID artifactId = createArtifact(3, true);
        UUID operationId = UUID.randomUUID();

        ArtifactDiscoveryResult first = artifacts.discover(operationId, player, artifactId, 1, "era-0");
        ArtifactDiscoveryResult retry = artifacts.discover(operationId, player, artifactId, 1, "era-0");
        ArtifactDiscoveryResult laterInteraction = artifacts.discover(
                UUID.randomUUID(), player, artifactId, 1, "era-0"
        );

        assertEquals(first, retry);
        assertTrue(first.newlyDiscovered());
        assertEquals(3L, first.totalAttunementPoints());
        assertFalse(laterInteraction.newlyDiscovered());
        assertEquals(first.discovery(), laterInteraction.discovery());
        assertEquals(3L, laterInteraction.totalAttunementPoints());
        assertEquals(1L, discoveryCount(player, artifactId));
        assertEquals(3L, artifacts.totalAttunementPoints(player));
        assertThrows(
                ArtifactException.class,
                () -> artifacts.discover(operationId, player, UUID.randomUUID(), 1, "era-0")
        );
    }

    @Test
    void relocationDoesNotErasePriorDiscoveryAndStaleLocationCannotCreateNewDiscovery() throws Exception {
        UUID firstPlayer = player("ArtifactMoveA");
        UUID secondPlayer = player("ArtifactMoveB");
        UUID artifactId = createArtifact(2, true);
        ArtifactDiscoveryResult first = artifacts.discover(
                UUID.randomUUID(), firstPlayer, artifactId, 1, "before-expansion"
        );

        artifacts.relocate(
                UUID.randomUUID(), artifactId, 1,
                "minecraft:overworld", "district.new", 90, 80, 90
        );

        assertEquals(1L, first.discovery().locationRevision());
        assertEquals(2L, artifacts.loadDefinition(artifactId).orElseThrow().currentLocation().locationRevision());
        assertEquals(2L, artifacts.totalAttunementPoints(firstPlayer));
        assertThrows(
                ArtifactException.class,
                () -> artifacts.discover(UUID.randomUUID(), secondPlayer, artifactId, 1, "after-expansion")
        );
        ArtifactDiscoveryResult second = artifacts.discover(
                UUID.randomUUID(), secondPlayer, artifactId, 2, "after-expansion"
        );
        assertEquals(2L, second.discovery().locationRevision());
    }

    @Test
    void concurrentDiscoveryForSamePlayerAndArtifactCanAwardOnlyOnce() throws Exception {
        UUID player = player("ArtifactRace");
        UUID artifactId = createArtifact(5, true);
        int newlyDiscovered = 0;

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ArtifactDiscoveryResult> first = executor.submit(
                    () -> artifacts.discover(UUID.randomUUID(), player, artifactId, 1, "race")
            );
            Future<ArtifactDiscoveryResult> second = executor.submit(
                    () -> artifacts.discover(UUID.randomUUID(), player, artifactId, 1, "race")
            );
            for (Future<ArtifactDiscoveryResult> future : List.of(first, second)) {
                ArtifactDiscoveryResult result = future.get();
                if (result.newlyDiscovered()) newlyDiscovered++;
            }
        }

        assertEquals(1, newlyDiscovered);
        assertEquals(1L, discoveryCount(player, artifactId));
        assertEquals(5L, artifacts.totalAttunementPoints(player));
    }

    @Test
    void disabledArtifactAndDatabaseForgeryAreRejected() throws Exception {
        UUID player = player("ArtifactDisabled");
        UUID disabled = UUID.randomUUID();
        artifacts.createArtifact(
                UUID.randomUUID(), disabled, 2, 1, false,
                "minecraft:overworld", "city", 1, 64, 1
        );
        assertThrows(
                ArtifactException.class,
                () -> artifacts.discover(UUID.randomUUID(), player, disabled, 1, "era")
        );

        UUID enabled = createArtifact(2, true);
        ArtifactDiscoveryResult discovery = artifacts.discover(
                UUID.randomUUID(), player, enabled, 1, "era"
        );
        assertThrows(SQLException.class, () -> mutateDiscovery(player, enabled));
        assertThrows(SQLException.class, () -> deleteDiscovery(player, enabled));
        assertThrows(SQLException.class, () -> insertForgedDiscovery(player("ArtifactForgery"), enabled, 99));
        assertEquals(discovery.discovery(), artifacts.listDiscoveries(player, 10).getFirst());
    }

    @Test
    void pointPolicyChangesAreVersionedAndNeverRewriteHistoricalAwards() throws Exception {
        UUID firstPlayer = player("ArtifactPolicyA");
        UUID secondPlayer = player("ArtifactPolicyB");
        UUID artifactId = createArtifact(2, true);
        ArtifactDiscoveryResult first = artifacts.discover(
                UUID.randomUUID(), firstPlayer, artifactId, 1, "policy-1"
        );

        updatePointPolicy(artifactId, 4, 2);
        ArtifactDiscoveryResult second = artifacts.discover(
                UUID.randomUUID(), secondPlayer, artifactId, 1, "policy-2"
        );

        assertEquals(2, first.discovery().pointsAwarded());
        assertEquals(1, first.discovery().pointPolicyVersion());
        assertEquals(4, second.discovery().pointsAwarded());
        assertEquals(2, second.discovery().pointPolicyVersion());
        assertEquals(2L, artifacts.totalAttunementPoints(firstPlayer));
        assertEquals(4L, artifacts.totalAttunementPoints(secondPlayer));
        assertThrows(SQLException.class, () -> updatePointPolicy(artifactId, 5, 4));
    }

    @Test
    void attunementUsesOneSharedDerivedPointPoolAcrossProfileSwitches() throws Exception {
        UUID player = player("AttuneSwitch");
        UUID artifactA = createArtifact(2, true);
        UUID artifactB = createArtifact(3, true);
        artifacts.discover(UUID.randomUUID(), player, artifactA, 1, "era");
        artifacts.discover(UUID.randomUUID(), player, artifactB, 1, "era");

        AttunementSnapshot neutral = attunement.loadOrInitialize(player);
        assertEquals(null, neutral.activeProfileId());
        assertEquals(5L, neutral.totalPoints());
        assertEquals(0L, neutral.stateVersion());

        UUID chooseOperation = UUID.randomUUID();
        AttunementSnapshot arcane = attunement.setActiveProfile(chooseOperation, player, "arcane");
        assertEquals(arcane, attunement.setActiveProfile(chooseOperation, player, "arcane"));
        assertEquals("arcane", arcane.activeProfileId());
        assertEquals(5L, arcane.totalPoints());
        assertEquals(1L, arcane.stateVersion());

        AttunementSnapshot sameProfile = attunement.setActiveProfile(UUID.randomUUID(), player, "arcane");
        assertEquals(1L, sameProfile.stateVersion());
        AttunementSnapshot martial = attunement.setActiveProfile(UUID.randomUUID(), player, "martial");
        assertEquals("martial", martial.activeProfileId());
        assertEquals(5L, martial.totalPoints());
        assertEquals(2L, martial.stateVersion());
        assertEquals(1L, attunementRowCount(player));
    }

    @Test
    void invalidProfileRebindingAndConcurrentSwitchesAreSafe() throws Exception {
        UUID player = player("AttuneRace");
        createArtifact(1, true);
        assertThrows(AttunementException.class, () -> attunement.setActiveProfile(
                UUID.randomUUID(), player, "unknown"
        ));

        UUID operationId = UUID.randomUUID();
        attunement.setActiveProfile(operationId, player, "arcane");
        assertThrows(
                AttunementException.class,
                () -> attunement.setActiveProfile(operationId, player, "martial")
        );

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AttunementSnapshot> first = executor.submit(
                    () -> attunement.setActiveProfile(UUID.randomUUID(), player, "arcane")
            );
            Future<AttunementSnapshot> second = executor.submit(
                    () -> attunement.setActiveProfile(UUID.randomUUID(), player, "martial")
            );
            first.get();
            second.get();
        }

        AttunementSnapshot finalState = attunement.loadOrInitialize(player);
        assertTrue("arcane".equals(finalState.activeProfileId()) || "martial".equals(finalState.activeProfileId()));
        assertEquals(1L, attunementRowCount(player));
        assertTrue(finalState.stateVersion() >= 2);
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private UUID createArtifact(int points, boolean enabled) throws SQLException {
        UUID artifactId = UUID.randomUUID();
        artifacts.createArtifact(
                UUID.randomUUID(), artifactId, points, 1, enabled,
                "minecraft:overworld", "city", 4, 65, 4
        );
        return artifactId;
    }

    private long artifactLocationCount(UUID artifactId) throws SQLException {
        return count("SELECT COUNT(*) FROM artifact_locations WHERE artifact_id = ?", artifactId);
    }

    private long discoveryCount(UUID playerId, UUID artifactId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM player_artifact_discoveries
                     WHERE player_id = ? AND artifact_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setObject(2, artifactId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long attunementRowCount(UUID playerId) throws SQLException {
        return count("SELECT COUNT(*) FROM player_attunement_state WHERE player_id = ?", playerId);
    }

    private long count(String sql, UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private void mutateHistoricalLocation(UUID artifactId, long revision) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE artifact_locations SET block_x = block_x + 1
                     WHERE artifact_id = ? AND location_revision = ?
                     """)) {
            statement.setObject(1, artifactId);
            statement.setLong(2, revision);
            statement.executeUpdate();
        }
    }

    private void insertSkippedLocationRevision(UUID artifactId, long revision) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO artifact_locations(
                         artifact_id, location_revision, operation_id, world_key, logical_zone_id, block_x, block_y, block_z
                     ) VALUES (?, ?, ?, 'minecraft:overworld', 'city', 1, 64, 1)
                     """)) {
            statement.setObject(1, artifactId);
            statement.setLong(2, revision);
            statement.setObject(3, UUID.randomUUID());
            statement.executeUpdate();
        }
    }

    private void mutateDiscovery(UUID playerId, UUID artifactId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_artifact_discoveries
                     SET points_awarded = points_awarded + 1
                     WHERE player_id = ? AND artifact_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setObject(2, artifactId);
            statement.executeUpdate();
        }
    }

    private void deleteDiscovery(UUID playerId, UUID artifactId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM player_artifact_discoveries
                     WHERE player_id = ? AND artifact_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setObject(2, artifactId);
            statement.executeUpdate();
        }
    }

    private void insertForgedDiscovery(UUID playerId, UUID artifactId, int points) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO player_artifact_discoveries(
                         player_id, artifact_id, operation_id, location_revision,
                         points_awarded, point_policy_version, world_era_context
                     ) VALUES (?, ?, ?, 1, ?, 1, 'forged')
                     """)) {
            statement.setObject(1, playerId);
            statement.setObject(2, artifactId);
            statement.setObject(3, UUID.randomUUID());
            statement.setInt(4, points);
            statement.executeUpdate();
        }
    }

    private void updatePointPolicy(UUID artifactId, int points, int policyVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE artifact_definitions
                     SET point_value = ?, point_policy_version = ?, updated_at = NOW()
                     WHERE artifact_id = ?
                     """)) {
            statement.setInt(1, points);
            statement.setInt(2, policyVersion);
            statement.setObject(3, artifactId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
