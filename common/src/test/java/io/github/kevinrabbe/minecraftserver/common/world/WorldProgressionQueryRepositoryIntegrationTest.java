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
class WorldProgressionQueryRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private MutableClock clock;
    private ExpansionVoteRepository votes;
    private WorldProgressionQueryRepository progression;

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
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
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
        progression = new WorldProgressionQueryRepository(dataSource);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void resolvedVoteProjectsCurrentEraAndAvailableFeatures() throws SQLException {
        assertTrue(progression.currentEra().isEmpty());
        assertTrue(progression.listFeatures(10).isEmpty());

        UUID voteId = UUID.randomUUID();
        votes.schedule(UUID.randomUUID(), new ExpansionVoteDefinition(
                voteId,
                1,
                clock.instant().minus(Duration.ofMinutes(1)),
                clock.instant().plus(Duration.ofHours(1)),
                List.of(
                        new ExpansionCandidate(
                                "fishing",
                                "Fishing District",
                                List.of("fishing", "boats"),
                                new WorldEraId("fishing_age")
                        ),
                        new ExpansionCandidate(
                                "logistics",
                                "Logistics District",
                                List.of("logistics"),
                                null
                        )
                )
        ), "vote.schedule");
        votes.open(UUID.randomUUID(), voteId, "vote.open");
        UUID a = identities.ensurePlayer(UUID.randomUUID(), "WorldA");
        UUID b = identities.ensurePlayer(UUID.randomUUID(), "WorldB");
        UUID c = identities.ensurePlayer(UUID.randomUUID(), "WorldC");
        votes.castBallot(UUID.randomUUID(), voteId, a, "fishing", "vote.cast");
        votes.castBallot(UUID.randomUUID(), voteId, b, "fishing", "vote.cast");
        votes.castBallot(UUID.randomUUID(), voteId, c, "logistics", "vote.cast");
        clock.advance(Duration.ofHours(2));
        UUID resolutionOperation = UUID.randomUUID();
        votes.resolve(resolutionOperation, voteId, "vote.resolve");

        WorldEraSnapshot era = progression.currentEra().orElseThrow();
        assertEquals(new WorldEraId("fishing_age"), era.eraId());
        assertEquals(0, era.sequenceNumber());
        assertEquals(resolutionOperation, era.sourceOperationId());

        List<FeatureState> features = progression.listFeatures(10);
        assertEquals(List.of("boats", "fishing"), features.stream().map(FeatureState::featureId).toList());
        assertTrue(features.stream().allMatch(feature -> feature.accessibility() == FeatureAccessibility.AVAILABLE));
        assertTrue(features.stream().allMatch(feature -> resolutionOperation.equals(feature.sourceOperationId())));
    }

    @Test
    void featureProjectionIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> progression.listFeatures(0));
        assertThrows(IllegalArgumentException.class, () -> progression.listFeatures(201));
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
