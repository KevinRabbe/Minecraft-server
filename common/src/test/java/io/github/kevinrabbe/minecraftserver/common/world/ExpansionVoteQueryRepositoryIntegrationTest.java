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
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ExpansionVoteQueryRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-03T18:00:00Z");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ExpansionVoteRepository votes;
    private ExpansionVoteQueryRepository queries;

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
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        votes = new ExpansionVoteRepository(dataSource, clock);
        queries = new ExpansionVoteQueryRepository(dataSource, clock);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void openVotesAreBoundedOrderedAndIncludeOnlyThisPlayersEffectiveBallot() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "Voter");
        UUID otherPlayerId = identities.ensurePlayer(UUID.randomUUID(), "OtherVoter");
        UUID earlier = scheduleAndOpen("earlier", Duration.ofMinutes(30));
        UUID later = scheduleAndOpen("later", Duration.ofHours(1));

        votes.castBallot(UUID.randomUUID(), later, otherPlayerId, "fishing", "vote.cast");
        List<ExpansionVoteView> before = queries.listOpen(playerId, 10);
        assertEquals(List.of(earlier, later), before.stream().map(view -> view.vote().voteId()).toList());
        assertEquals(List.of("fishing", "logistics"), before.getFirst().candidates().stream()
                .map(ExpansionCandidate::candidateId).toList());
        assertNull(before.get(1).ballot());

        votes.castBallot(UUID.randomUUID(), later, playerId, "logistics", "vote.cast");
        ExpansionVoteView selected = queries.listOpen(playerId, 10).get(1);
        assertEquals("logistics", selected.ballot().candidateId());
        assertEquals(playerId, selected.ballot().playerId());

        votes.castBallot(UUID.randomUUID(), later, playerId, "fishing", "vote.cast");
        ExpansionVoteView changed = queries.listOpen(playerId, 10).get(1);
        assertEquals("fishing", changed.ballot().candidateId());
        assertEquals(2, queries.listOpen(playerId, 2).size());
    }

    @Test
    void expiredOpenStatusIsNotPresentedAsCastable() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "LateVoter");
        scheduleAndOpen("short", Duration.ofMinutes(15));

        ExpansionVoteQueryRepository afterClose = new ExpansionVoteQueryRepository(
                dataSource,
                Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC)
        );
        assertTrue(afterClose.listOpen(playerId, 10).isEmpty());
    }

    @Test
    void queryLimitIsBounded() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "BoundedVoter");
        assertThrows(IllegalArgumentException.class, () -> queries.listOpen(playerId, 0));
        assertThrows(IllegalArgumentException.class, () -> queries.listOpen(playerId, 21));
    }

    private UUID scheduleAndOpen(String seed, Duration remaining) throws SQLException {
        UUID voteId = UUID.nameUUIDFromBytes(("vote:" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ExpansionVoteDefinition definition = new ExpansionVoteDefinition(
                voteId,
                1,
                NOW.minus(Duration.ofMinutes(1)),
                NOW.plus(remaining),
                List.of(
                        new ExpansionCandidate("fishing", "Fishing District", List.of("fishing"), null),
                        new ExpansionCandidate("logistics", "Logistics District", List.of("logistics"), null)
                )
        );
        votes.schedule(UUID.randomUUID(), definition, "vote.schedule");
        votes.open(UUID.randomUUID(), voteId, "vote.open");
        return voteId;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
