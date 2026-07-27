package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionCandidate;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteDefinition;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteRepository;
import io.github.kevinrabbe.minecraftserver.common.world.ExpansionVoteSnapshot;
import io.github.kevinrabbe.minecraftserver.common.world.WorldEraId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class WorldProgressionIntegrityVerifierIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private WorldProgressionIntegrityVerifier verifier;

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
        verifier = new WorldProgressionIntegrityVerifier(dataSource);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void resolvedVoteConsequencesReconcileAndTamperingIsDetected() throws Exception {
        ResolvedFixture fixture = createResolvedVote();
        String voteSubject = fixture.voteId().toString();

        assertFalse(verifier.verify(10_000).stream().anyMatch(issue ->
                issue.subjectId().startsWith(voteSubject)
        ));
        assertFalse(new PersistentIntegrityVerifier(dataSource).verify(10_000).stream().anyMatch(issue ->
                issue.subjectId().startsWith(voteSubject)
        ));

        UUID forgedOperation = UUID.randomUUID();
        setResolutionOperation(fixture.voteId(), forgedOperation);
        try {
            assertIssue("EXPANSION_RESOLUTION_EVIDENCE_MISMATCH", voteSubject);
        } finally {
            setResolutionOperation(fixture.voteId(), fixture.resolutionOperationId());
        }

        setWinningCandidate(fixture.voteId(), fixture.losingCandidateId());
        try {
            assertIssue("EXPANSION_HISTORY_MISMATCH", voteSubject);
        } finally {
            setWinningCandidate(fixture.voteId(), fixture.winningCandidateId());
        }

        setFeatureAccessibility(fixture.featureId(), "LOCKED");
        try {
            assertIssue("EXPANSION_FEATURE_STATE_MISMATCH", voteSubject + "/" + fixture.featureId());
        } finally {
            setFeatureAccessibility(fixture.featureId(), "AVAILABLE");
        }

        Instant shiftedResolution = fixture.resolvedAt().plusSeconds(1);
        setResolvedAt(fixture.voteId(), shiftedResolution);
        try {
            assertIssue("EXPANSION_WORLD_ERA_MISMATCH", voteSubject + "/" + fixture.worldEraId());
        } finally {
            setResolvedAt(fixture.voteId(), fixture.resolvedAt());
        }

        assertFalse(verifier.verify(10_000).stream().anyMatch(issue ->
                issue.subjectId().startsWith(voteSubject)
        ));
    }

    private ResolvedFixture createResolvedVote() throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String winningCandidate = "winner_" + suffix;
        String losingCandidate = "loser_" + suffix;
        String featureId = "feature_" + suffix;
        String losingFeatureId = "other_" + suffix;
        String eraId = "era_" + suffix;
        UUID voteId = UUID.randomUUID();

        MutableClock clock = new MutableClock(Instant.parse("2026-08-01T18:00:00Z"));
        ExpansionVoteRepository votes = new ExpansionVoteRepository(dataSource, clock);
        votes.schedule(
                UUID.randomUUID(),
                new ExpansionVoteDefinition(
                        voteId,
                        1,
                        clock.instant().minus(Duration.ofMinutes(1)),
                        clock.instant().plus(Duration.ofHours(1)),
                        List.of(
                                new ExpansionCandidate(
                                        winningCandidate,
                                        "Winning district",
                                        List.of(featureId),
                                        new WorldEraId(eraId)
                                ),
                                new ExpansionCandidate(
                                        losingCandidate,
                                        "Other district",
                                        List.of(losingFeatureId),
                                        null
                                )
                        )
                ),
                "verify.world.schedule"
        );
        votes.open(UUID.randomUUID(), voteId, "verify.world.open");

        UUID first = identities.ensurePlayer(UUID.randomUUID(), "WorldVoteA");
        UUID second = identities.ensurePlayer(UUID.randomUUID(), "WorldVoteB");
        votes.castBallot(UUID.randomUUID(), voteId, first, winningCandidate, "verify.world.ballot");
        votes.castBallot(UUID.randomUUID(), voteId, second, winningCandidate, "verify.world.ballot");

        clock.advance(Duration.ofHours(2));
        UUID resolutionOperation = UUID.randomUUID();
        votes.resolve(resolutionOperation, voteId, "verify.world.resolve");
        ExpansionVoteSnapshot resolved = votes.load(voteId);

        return new ResolvedFixture(
                voteId,
                winningCandidate,
                losingCandidate,
                featureId,
                eraId,
                resolutionOperation,
                resolved.resolvedAt()
        );
    }

    private void assertIssue(String code, String subject) throws SQLException {
        assertTrue(verifier.verify(10_000).stream().anyMatch(issue ->
                issue.severity() == IntegritySeverity.CRITICAL
                        && issue.code().equals(code)
                        && issue.subjectId().equals(subject)
        ));
    }

    private void setResolutionOperation(UUID voteId, UUID operationId) throws SQLException {
        updateVote("resolution_operation_id = ?", voteId, statement -> statement.setObject(1, operationId));
    }

    private void setWinningCandidate(UUID voteId, String candidateId) throws SQLException {
        updateVote("winning_candidate_id = ?", voteId, statement -> statement.setString(1, candidateId));
    }

    private void setResolvedAt(UUID voteId, Instant resolvedAt) throws SQLException {
        updateVote("resolved_at = ?", voteId, statement -> statement.setTimestamp(1, Timestamp.from(resolvedAt)));
    }

    private void setFeatureAccessibility(String featureId, String accessibility) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE feature_states
                     SET accessibility = ?
                     WHERE feature_id = ?
                     """)) {
            statement.setString(1, accessibility);
            statement.setString(2, featureId);
            if (statement.executeUpdate() != 1) {
                throw new AssertionError("Expected one feature state row for " + featureId);
            }
        }
    }

    private void updateVote(String assignment, UUID voteId, SqlBinder binder) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE expansion_votes SET " + assignment + " WHERE vote_id = ?"
             )) {
            binder.bind(statement);
            statement.setObject(2, voteId);
            if (statement.executeUpdate() != 1) {
                throw new AssertionError("Expected one expansion vote row for " + voteId);
            }
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private record ResolvedFixture(
            UUID voteId,
            String winningCandidateId,
            String losingCandidateId,
            String featureId,
            String worldEraId,
            UUID resolutionOperationId,
            Instant resolvedAt
    ) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Test clock supports UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
