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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ExpansionVoteLifecycleServiceIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private MutableClock clock;
    private ExpansionVoteRepository votes;
    private ExpansionVoteLifecycleQueryRepository queries;
    private ExpansionVoteLifecycleService lifecycle;

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
        queries = new ExpansionVoteLifecycleQueryRepository(dataSource, clock);
        lifecycle = new ExpansionVoteLifecycleService(queries, votes, 20);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void opensAndResolvesDueVoteExactlyOnce() throws SQLException {
        UUID voteId = UUID.randomUUID();
        votes.schedule(UUID.randomUUID(), definition(voteId), "vote.schedule");

        ExpansionVoteLifecycleAdvanceResult opened = lifecycle.advanceOnce();
        assertEquals(new ExpansionVoteLifecycleAdvanceResult(1, 0, 0), opened);
        assertEquals(ExpansionVoteStatus.OPEN, votes.load(voteId).status());

        UUID a = identities.ensurePlayer(UUID.randomUUID(), "LifeA");
        UUID b = identities.ensurePlayer(UUID.randomUUID(), "LifeB");
        UUID c = identities.ensurePlayer(UUID.randomUUID(), "LifeC");
        votes.castBallot(UUID.randomUUID(), voteId, a, "fishing", "vote.cast");
        votes.castBallot(UUID.randomUUID(), voteId, b, "fishing", "vote.cast");
        votes.castBallot(UUID.randomUUID(), voteId, c, "logistics", "vote.cast");

        clock.advance(Duration.ofHours(2));
        ExpansionVoteLifecycleAdvanceResult resolved = lifecycle.advanceOnce();
        assertEquals(new ExpansionVoteLifecycleAdvanceResult(0, 1, 0), resolved);
        assertEquals(ExpansionVoteStatus.RESOLVED, votes.load(voteId).status());
        assertEquals(FeatureAccessibility.AVAILABLE, votes.findFeature("fishing").orElseThrow().accessibility());
        assertEquals(1L, count("historical_events"));

        assertEquals(new ExpansionVoteLifecycleAdvanceResult(0, 0, 0), lifecycle.advanceOnce());
    }

    @Test
    void tiedVoteRemainsOpenForExplicitRunoff() throws SQLException {
        UUID voteId = UUID.randomUUID();
        votes.schedule(UUID.randomUUID(), definition(voteId), "vote.schedule");
        lifecycle.advanceOnce();

        UUID a = identities.ensurePlayer(UUID.randomUUID(), "TieLifeA");
        UUID b = identities.ensurePlayer(UUID.randomUUID(), "TieLifeB");
        votes.castBallot(UUID.randomUUID(), voteId, a, "fishing", "vote.cast");
        votes.castBallot(UUID.randomUUID(), voteId, b, "logistics", "vote.cast");

        clock.advance(Duration.ofHours(2));
        ExpansionVoteLifecycleAdvanceResult result = lifecycle.advanceOnce();
        assertEquals(new ExpansionVoteLifecycleAdvanceResult(0, 0, 1), result);
        assertEquals(ExpansionVoteStatus.OPEN, votes.load(voteId).status());
        assertEquals(0L, count("historical_events"));
    }

    @Test
    void lifecycleQueriesAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> queries.listOpenable(0));
        assertThrows(IllegalArgumentException.class, () -> queries.listResolvable(101));
    }

    private ExpansionVoteDefinition definition(UUID voteId) {
        return new ExpansionVoteDefinition(
                voteId,
                1,
                clock.instant().minus(Duration.ofMinutes(1)),
                clock.instant().plus(Duration.ofHours(1)),
                List.of(
                        new ExpansionCandidate("fishing", "Fishing District", List.of("fishing"), null),
                        new ExpansionCandidate("logistics", "Logistics District", List.of("logistics"), null)
                )
        );
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
