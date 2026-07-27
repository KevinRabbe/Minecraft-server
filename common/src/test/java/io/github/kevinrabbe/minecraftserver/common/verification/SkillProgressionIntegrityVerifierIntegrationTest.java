package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.ActiveSkillCapState;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressSnapshot;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SkillProgressionIntegrityVerifierIntegrationTest {
    private static final SkillId MINING = new SkillId("mining");
    private static final String UNKNOWN_SKILL = "verify.unknown_skill";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private SkillProgressionCatalog catalog;
    private SkillProgressionRepository progression;
    private SkillProgressionIntegrityVerifier verifier;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                4
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        catalog = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");
        progression = new SkillProgressionRepository(dataSource, catalog);
        verifier = new SkillProgressionIntegrityVerifier(dataSource, catalog);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void healthyCommittedAwardReconcilesAndAggregateVerifierIncludesProgression() throws Exception {
        UUID playerId = player("SkillHealthy");
        progression.awardExperience(UUID.randomUUID(), playerId, MINING, 250L, "verify.skill_integrity");

        String subject = playerId + "/" + MINING.value();
        assertFalse(verifier.verify(10_000).stream().anyMatch(issue -> subject.equals(issue.subjectId())));
        assertFalse(new PersistentIntegrityVerifier(dataSource, null, catalog).verify(10_000).stream()
                .anyMatch(issue -> subject.equals(issue.subjectId())));
    }

    @Test
    void mutableSkillStateThatNoLongerMatchesAppendOnlyAwardsIsCritical() throws Exception {
        UUID playerId = player("SkillEvidence");
        progression.awardExperience(UUID.randomUUID(), playerId, MINING, 250L, "verify.skill_integrity");
        SkillProgressSnapshot committed = progression.load(playerId, MINING);

        setSkillState(playerId, MINING.value(), committed.experience() + 1L, committed.stateVersion());
        try {
            List<IntegrityIssue> issues = verifier.verify(10_000);
            String subject = playerId + "/" + MINING.value();
            assertTrue(issues.stream().anyMatch(issue ->
                    issue.severity() == IntegritySeverity.CRITICAL
                            && issue.code().equals("SKILL_PROGRESS_EVIDENCE_MISMATCH")
                            && subject.equals(issue.subjectId())
            ));
        } finally {
            setSkillState(playerId, MINING.value(), committed.experience(), committed.stateVersion());
        }
    }

    @Test
    void unknownPersistedSkillDefinitionIsCritical() throws Exception {
        UUID playerId = player("SkillUnknown");
        insertSkillState(playerId, UNKNOWN_SKILL, 0L, 0L);
        try {
            String subject = playerId + "/" + UNKNOWN_SKILL;
            assertTrue(verifier.verify(10_000).stream().anyMatch(issue ->
                    issue.severity() == IntegritySeverity.CRITICAL
                            && issue.code().equals("SKILL_DEFINITION_UNKNOWN")
                            && subject.equals(issue.subjectId())
            ));
        } finally {
            deleteSkillState(playerId, UNKNOWN_SKILL);
        }
    }

    @Test
    void persistedExperienceAboveCurrentActiveCapIsCritical() throws Exception {
        UUID playerId = player("SkillCap");
        progression.awardExperience(UUID.randomUUID(), playerId, MINING, 250L, "verify.skill_integrity");
        SkillProgressSnapshot committed = progression.load(playerId, MINING);
        ActiveSkillCapState activeCap = progression.loadActiveCap();
        long ceiling = catalog.require(MINING).experienceForLevel(activeCap.activeCap());

        setSkillState(playerId, MINING.value(), Math.addExact(ceiling, 1L), committed.stateVersion());
        try {
            String subject = playerId + "/" + MINING.value();
            assertTrue(verifier.verify(10_000).stream().anyMatch(issue ->
                    issue.severity() == IntegritySeverity.CRITICAL
                            && issue.code().equals("SKILL_ACTIVE_CAP_EXCEEDED")
                            && subject.equals(issue.subjectId())
            ));
        } finally {
            setSkillState(playerId, MINING.value(), committed.experience(), committed.stateVersion());
        }
    }

    @Test
    void stagedCapWithoutMatchingProcessedOperationIsCritical() throws Exception {
        ProgressionStateRow original = readProgressionState();
        setProgressionState(75, 1L, UUID.randomUUID(), Timestamp.from(java.time.Instant.now()));
        try {
            assertTrue(verifier.verify(10_000).stream().anyMatch(issue ->
                    issue.severity() == IntegritySeverity.CRITICAL
                            && issue.code().equals("SKILL_CAP_EVIDENCE_MISMATCH")
                            && issue.subjectId().equals("progression_state")
            ));
        } finally {
            setProgressionState(
                    original.activeCap(),
                    original.stateVersion(),
                    original.sourceOperationId(),
                    original.changedAt()
            );
        }
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void insertSkillState(UUID playerId, String skillId, long experience, long stateVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO player_skills(player_id, skill_id, experience, state_version)
                     VALUES (?, ?, ?, ?)
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, skillId);
            statement.setLong(3, experience);
            statement.setLong(4, stateVersion);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void setSkillState(UUID playerId, String skillId, long experience, long stateVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_skills
                     SET experience = ?, state_version = ?, updated_at = NOW()
                     WHERE player_id = ? AND skill_id = ?
                     """)) {
            statement.setLong(1, experience);
            statement.setLong(2, stateVersion);
            statement.setObject(3, playerId);
            statement.setString(4, skillId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void deleteSkillState(UUID playerId, String skillId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM player_skills
                     WHERE player_id = ? AND skill_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, skillId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private ProgressionStateRow readProgressionState() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT active_skill_cap, state_version, source_operation_id, changed_at
                     FROM progression_state
                     WHERE singleton = TRUE
                     """);
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new AssertionError("progression_state row is missing");
            }
            return new ProgressionStateRow(
                    row.getInt("active_skill_cap"),
                    row.getLong("state_version"),
                    row.getObject("source_operation_id", UUID.class),
                    row.getTimestamp("changed_at")
            );
        }
    }

    private void setProgressionState(
            int activeCap,
            long stateVersion,
            UUID sourceOperationId,
            Timestamp changedAt
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE progression_state
                     SET active_skill_cap = ?,
                         state_version = ?,
                         source_operation_id = ?,
                         changed_at = ?
                     WHERE singleton = TRUE
                     """)) {
            statement.setInt(1, activeCap);
            statement.setLong(2, stateVersion);
            statement.setObject(3, sourceOperationId);
            statement.setTimestamp(4, changedAt);
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

    private record ProgressionStateRow(
            int activeCap,
            long stateVersion,
            UUID sourceOperationId,
            Timestamp changedAt
    ) {
    }
}
