package io.github.kevinrabbe.minecraftserver.common.world;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ExpansionVoteRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private MutableClock clock;
    private ExpansionVoteRepository votes;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                8
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        historical_events,
                        expansion_ballots,
                        expansion_vote_candidates,
                        expansion_votes,
                        feature_states,
                        world_eras,
                        processed_operations,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
        clock = new MutableClock(Instant.parse("2026-08-01T18:00:00Z"));
        votes = new ExpansionVoteRepository(dataSource, clock);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void majorityResolutionUnlocksCapabilityAndWritesHistory() throws SQLException {
        UUID voteId = scheduleAndOpenVote(null);
        UUID a = newPlayer("A");
        UUID b = newPlayer("B");
        UUID c = newPlayer("C");
        votes.castBallot(UUID.randomUUID(), voteId, a, "fishing", "vote.cast");
        votes.castBallot(UUID.randomUUID(), voteId, b, "fishing", "vote.cast");
        votes.castBallot(UUID.randomUUID(), voteId, c, "logistics", "vote.cast");

        clock.advance(Duration.ofHours(2));
        ExpansionVoteResult result = votes.resolve(UUID.randomUUID(), voteId, "vote.resolve");

        assertEquals("fishing", result.winningCandidateId());
        assertEquals(2L, result.ballotCounts().get("fishing"));
        assertEquals(FeatureAccessibility.AVAILABLE, votes.findFeature("fishing").orElseThrow().accessibility());
        assertEquals(ExpansionVoteStatus.RESOLVED, votes.load(voteId).status());
        assertEquals(1L, count("historical_events"));
    }

    @Test
    void playerMayChangeBallotButOnlyLatestEffectiveBallotCounts() throws SQLException {
        UUID voteId = scheduleAndOpenVote(null);
        UUID playerId = newPlayer("SwingVoter");
        votes.castBallot(UUID.randomUUID(), voteId, playerId, "fishing", "vote.cast");
        votes.castBallot(UUID.randomUUID(), voteId, playerId, "logistics", "vote.cast");
        UUID second = newPlayer("Second");
        votes.castBallot(UUID.randomUUID(), voteId, second, "logistics", "vote.cast");

        clock.advance(Duration.ofHours(2));
        ExpansionVoteResult result = votes.resolve(UUID.randomUUID(), voteId, "vote.resolve");

        assertEquals("logistics", result.winningCandidateId());
        assertEquals(0L, result.ballotCounts().get("fishing"));
        assertEquals(2L, result.ballotCounts().get("logistics"));
        assertEquals(2L, count("expansion_ballots"));
    }

    @Test
    void tieDoesNotChooseDeveloperAuthoredWinner() throws SQLException {
        UUID voteId = scheduleAndOpenVote(null);
        UUID a = newPlayer("TieA");
        UUID b = newPlayer("TieB");
        votes.castBallot(UUID.randomUUID(), voteId, a, "fishing", "vote.cast");
        votes.castBallot(UUID.randomUUID(), voteId, b, "logistics", "vote.cast");
        clock.advance(Duration.ofHours(2));

        ExpansionVoteTieException tie = assertThrows(
                ExpansionVoteTieException.class,
                () -> votes.resolve(UUID.randomUUID(), voteId, "vote.resolve")
        );

        assertEquals(List.of("fishing", "logistics").stream().sorted().toList(),
                tie.tiedCandidateIds().stream().sorted().toList());
        assertEquals(ExpansionVoteStatus.OPEN, votes.load(voteId).status());
        assertTrue(votes.findFeature("fishing").isEmpty());
        assertTrue(votes.findFeature("logistics").isEmpty());
        assertEquals(0L, count("historical_events"));
    }

    @Test
    void resultingWorldEraStartsOnlyForWinningCandidate() throws SQLException {
        UUID voteId = scheduleAndOpenVote(new WorldEraId("nether"));
        UUID a = newPlayer("EraA");
        UUID b = newPlayer("EraB");
        votes.castBallot(UUID.randomUUID(), voteId, a, "fishing", "vote.cast");
        votes.castBallot(UUID.randomUUID(), voteId, b, "fishing", "vote.cast");
        clock.advance(Duration.ofHours(2));

        votes.resolve(UUID.randomUUID(), voteId, "vote.resolve");

        assertEquals(1L, count("world_eras"));
    }

    @Test
    void operationRetriesAreExactAndBoundToOriginalRequest() throws SQLException {
        UUID voteId = UUID.randomUUID();
        ExpansionVoteDefinition definition = definition(voteId, null);
        UUID scheduleOperation = UUID.randomUUID();

        ExpansionVoteSnapshot first = votes.schedule(scheduleOperation, definition, "vote.schedule");
        ExpansionVoteSnapshot retry = votes.schedule(scheduleOperation, definition, "vote.schedule");
        assertEquals(first, retry);

        assertThrows(
                ExpansionVoteException.class,
                () -> votes.schedule(
                        scheduleOperation,
                        new ExpansionVoteDefinition(
                                voteId,
                                1,
                                clock.instant(),
                                clock.instant().plus(Duration.ofHours(2)),
                                definition.candidates()
                        ),
                        "vote.schedule"
                )
        );
    }

    @Test
    void voteCannotResolveBeforeConfiguredClose() throws SQLException {
        UUID voteId = scheduleAndOpenVote(null);
        UUID playerId = newPlayer("Early");
        votes.castBallot(UUID.randomUUID(), voteId, playerId, "fishing", "vote.cast");

        assertThrows(
                ExpansionVoteException.class,
                () -> votes.resolve(UUID.randomUUID(), voteId, "vote.resolve")
        );
    }

    private UUID scheduleAndOpenVote(WorldEraId fishingEra) throws SQLException {
        UUID voteId = UUID.randomUUID();
        votes.schedule(UUID.randomUUID(), definition(voteId, fishingEra), "vote.schedule");
        votes.open(UUID.randomUUID(), voteId, "vote.open");
        return voteId;
    }

    private ExpansionVoteDefinition definition(UUID voteId, WorldEraId fishingEra) {
        return new ExpansionVoteDefinition(
                voteId,
                1,
                clock.instant().minus(Duration.ofMinutes(1)),
                clock.instant().plus(Duration.ofHours(1)),
                List.of(
                        new ExpansionCandidate(
                                "fishing",
                                "Fishing District",
                                List.of("fishing"),
                                fishingEra
                        ),
                        new ExpansionCandidate(
                                "logistics",
                                "Logistics District",
                                List.of("logistics"),
                                null
                        )
                )
        );
    }

    private UUID newPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private long count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
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
