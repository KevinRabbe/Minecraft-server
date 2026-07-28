package io.github.kevinrabbe.minecraftserver.common.pvp;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class RankedArenaRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private RankedArenaRepository arena;

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
        arena = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        ranked_match_results,
                        ranked_match_participants,
                        ranked_matches,
                        ranked_ratings,
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
    void createMatchCreatesInitialRatingsLiveParticipantsAndExactRetry() throws Exception {
        UUID playerA = player("ArenaCreateA");
        UUID playerB = player("ArenaCreateB");
        UUID operationId = UUID.randomUUID();

        RankedMatchSnapshot first = arena.createMatch(operationId, playerA, playerB);
        RankedMatchSnapshot retry = arena.createMatch(operationId, playerA, playerB);

        assertEquals(first, retry);
        assertEquals(RankedMatchStatus.CREATED, first.status());
        assertEquals("arena.legacy_1_8_9", first.rulesetId());
        assertEquals(1, first.rulesetVersion());
        assertEquals(1, first.ratingPolicyVersion());
        assertEquals(32, first.ratingKFactor());
        assertEquals(1000, arena.loadRating(playerA).orElseThrow().rating());
        assertEquals(1000, arena.loadRating(playerB).orElseThrow().rating());
        assertEquals(2L, liveParticipantCount(first.matchId()));

        assertThrows(
                RankedArenaException.class,
                () -> arena.createMatch(operationId, playerB, playerA)
        );
    }

    @Test
    void onePlayerCannotEnterTwoLiveMatchesAndConcurrentCreatesHaveOneWinner() throws Exception {
        UUID shared = player("ArenaShared");
        UUID opponentA = player("ArenaOppA");
        UUID opponentB = player("ArenaOppB");

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RankedMatchSnapshot> first = executor.submit(
                    () -> arena.createMatch(UUID.randomUUID(), shared, opponentA)
            );
            Future<RankedMatchSnapshot> second = executor.submit(
                    () -> arena.createMatch(UUID.randomUUID(), shared, opponentB)
            );
            for (Future<RankedMatchSnapshot> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof RankedArenaException
                            || expected.getCause() instanceof SQLException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1L, liveMatchesForPlayer(shared));
    }

    @Test
    void startAndCancelAreIdempotentAndCancellationReleasesParticipantsWithoutRatingChange() throws Exception {
        UUID playerA = player("ArenaCancelA");
        UUID playerB = player("ArenaCancelB");
        RankedMatchSnapshot created = arena.createMatch(UUID.randomUUID(), playerA, playerB);
        UUID startOperation = UUID.randomUUID();
        RankedMatchSnapshot active = arena.startMatch(startOperation, created.matchId());
        assertEquals(active, arena.startMatch(startOperation, created.matchId()));
        assertEquals(RankedMatchStatus.ACTIVE, active.status());

        UUID cancelOperation = UUID.randomUUID();
        RankedMatchSnapshot cancelled = arena.cancelMatch(cancelOperation, created.matchId());
        assertEquals(cancelled, arena.cancelMatch(cancelOperation, created.matchId()));
        assertEquals(RankedMatchStatus.CANCELLED, cancelled.status());
        assertEquals(0L, liveParticipantCount(created.matchId()));
        assertEquals(1000, arena.loadRating(playerA).orElseThrow().rating());
        assertEquals(1000, arena.loadRating(playerB).orElseThrow().rating());

        UUID third = player("ArenaCancelC");
        RankedMatchSnapshot next = arena.createMatch(UUID.randomUUID(), playerA, third);
        assertEquals(RankedMatchStatus.CREATED, next.status());
    }

    @Test
    void completionSettlesEqualRatingsExactlyOnceAndWritesImmutableHistoricalEvidence() throws Exception {
        UUID playerA = player("ArenaWinA");
        UUID playerB = player("ArenaWinB");
        RankedMatchSnapshot match = arena.createMatch(UUID.randomUUID(), playerA, playerB);
        arena.startMatch(UUID.randomUUID(), match.matchId());
        UUID completionOperation = UUID.randomUUID();

        RankedMatchResult first = arena.completeMatch(completionOperation, match.matchId(), playerA);
        RankedMatchResult retry = arena.completeMatch(completionOperation, match.matchId(), playerA);

        assertEquals(first, retry);
        assertEquals(RankedMatchStatus.COMPLETED, first.match().status());
        assertEquals(playerA, first.match().winnerPlayerId());
        assertEquals(playerB, first.loserPlayerId());
        assertEquals(1000, first.playerABefore().rating());
        assertEquals(1016, first.playerAAfter().rating());
        assertEquals(1000, first.playerBBefore().rating());
        assertEquals(984, first.playerBAfter().rating());
        assertEquals(
                first.playerABefore().rating() + first.playerBBefore().rating(),
                first.playerAAfter().rating() + first.playerBAfter().rating()
        );
        assertEquals(0L, liveParticipantCount(match.matchId()));
        assertEquals(1L, resultCount(match.matchId()));
        assertEquals("arena.legacy_1_8_9", resultRuleset(match.matchId()));
        assertEquals(32, resultKFactor(match.matchId()));

        assertThrows(SQLException.class, () -> mutateResult(match.matchId()));
        assertThrows(SQLException.class, () -> deleteResult(match.matchId()));
        assertThrows(SQLException.class, () -> mutateTerminalMatch(match.matchId()));
    }

    @Test
    void invalidWinnerAndPrematureCompletionDoNotChangeRatings() throws Exception {
        UUID playerA = player("ArenaInvalidA");
        UUID playerB = player("ArenaInvalidB");
        UUID outsider = player("ArenaInvalidC");
        RankedMatchSnapshot created = arena.createMatch(UUID.randomUUID(), playerA, playerB);

        assertThrows(
                RankedArenaException.class,
                () -> arena.completeMatch(UUID.randomUUID(), created.matchId(), playerA)
        );
        arena.startMatch(UUID.randomUUID(), created.matchId());
        assertThrows(
                RankedArenaException.class,
                () -> arena.completeMatch(UUID.randomUUID(), created.matchId(), outsider)
        );

        assertEquals(1000, arena.loadRating(playerA).orElseThrow().rating());
        assertEquals(1000, arena.loadRating(playerB).orElseThrow().rating());
        assertEquals(RankedMatchStatus.ACTIVE, arena.loadMatch(created.matchId()).orElseThrow().status());
    }

    @Test
    void concurrentCompletionCanSettleRatingsOnlyOnce() throws Exception {
        UUID playerA = player("ArenaRaceA");
        UUID playerB = player("ArenaRaceB");
        RankedMatchSnapshot match = arena.createMatch(UUID.randomUUID(), playerA, playerB);
        arena.startMatch(UUID.randomUUID(), match.matchId());

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RankedMatchResult> first = executor.submit(
                    () -> arena.completeMatch(UUID.randomUUID(), match.matchId(), playerA)
            );
            Future<RankedMatchResult> second = executor.submit(
                    () -> arena.completeMatch(UUID.randomUUID(), match.matchId(), playerB)
            );
            for (Future<RankedMatchResult> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof RankedArenaException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1L, resultCount(match.matchId()));
        assertEquals(2000,
                arena.loadRating(playerA).orElseThrow().rating()
                        + arena.loadRating(playerB).orElseThrow().rating());
    }

    @Test
    void inFlightMatchKeepsFrozenRatingPolicyAcrossRepositoryConfigChange() throws Exception {
        UUID playerA = player("ArenaFreezeA");
        UUID playerB = player("ArenaFreezeB");
        RankedMatchSnapshot match = arena.createMatch(UUID.randomUUID(), playerA, playerB);
        arena.startMatch(UUID.randomUUID(), match.matchId());

        RankedArenaRepository changedDeployment = new RankedArenaRepository(
                dataSource,
                new RankedArenaRuleset("arena.legacy_1_8_9", 2, 2, 1200, 96)
        );
        RankedMatchResult result = changedDeployment.completeMatch(
                UUID.randomUUID(), match.matchId(), playerA
        );

        assertEquals(1, result.match().rulesetVersion());
        assertEquals(1, result.ratingPolicyVersion());
        assertEquals(32, result.ratingKFactor());
        assertEquals(1016, result.playerAAfter().rating());
        assertEquals(984, result.playerBAfter().rating());
    }

    @Test
    void leaderboardOrdersByRatingAndDatabaseRejectsBogusParticipantAndCompletionWithoutResult() throws Exception {
        UUID playerA = player("ArenaTopA");
        UUID playerB = player("ArenaTopB");
        UUID playerC = player("ArenaTopC");
        RankedMatchSnapshot match = arena.createMatch(UUID.randomUUID(), playerA, playerB);
        arena.startMatch(UUID.randomUUID(), match.matchId());
        arena.completeMatch(UUID.randomUUID(), match.matchId(), playerA);

        List<RankedRatingSnapshot> top = arena.topRatings(10);
        assertEquals(playerA, top.getFirst().playerId());
        assertEquals(1016, top.getFirst().rating());
        assertTrue(top.stream().anyMatch(value -> value.playerId().equals(playerB) && value.rating() == 984));
        assertFalse(top.stream().anyMatch(value -> value.playerId().equals(playerC)));

        RankedMatchSnapshot another = arena.createMatch(UUID.randomUUID(), playerB, playerC);
        assertThrows(SQLException.class, () -> insertBogusParticipant(another.matchId(), playerA));
        arena.startMatch(UUID.randomUUID(), another.matchId());
        assertThrows(SQLException.class, () -> rawCompleteWithoutResult(another.matchId(), playerB));
        assertEquals(RankedMatchStatus.ACTIVE, arena.loadMatch(another.matchId()).orElseThrow().status());
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private long liveParticipantCount(UUID matchId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM ranked_match_participants
                     WHERE match_id = ? AND released_at IS NULL
                     """)) {
            statement.setObject(1, matchId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long liveMatchesForPlayer(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM ranked_match_participants
                     WHERE player_id = ? AND released_at IS NULL
                     """)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long resultCount(UUID matchId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ranked_match_results WHERE match_id = ?")) {
            statement.setObject(1, matchId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private String resultRuleset(UUID matchId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT ruleset_id FROM ranked_match_results WHERE match_id = ?")) {
            statement.setObject(1, matchId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private int resultKFactor(UUID matchId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT rating_k_factor FROM ranked_match_results WHERE match_id = ?")) {
            statement.setObject(1, matchId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getInt(1);
            }
        }
    }

    private void mutateResult(UUID matchId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE ranked_match_results
                     SET player_a_rating_after = player_a_rating_after + 1
                     WHERE match_id = ?
                     """)) {
            statement.setObject(1, matchId);
            statement.executeUpdate();
        }
    }

    private void deleteResult(UUID matchId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM ranked_match_results WHERE match_id = ?")) {
            statement.setObject(1, matchId);
            statement.executeUpdate();
        }
    }

    private void mutateTerminalMatch(UUID matchId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE ranked_matches
                     SET state_version = state_version + 1
                     WHERE match_id = ?
                     """)) {
            statement.setObject(1, matchId);
            statement.executeUpdate();
        }
    }

    private void insertBogusParticipant(UUID matchId, UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ranked_match_participants(match_id, player_id)
                     VALUES (?, ?)
                     """)) {
            statement.setObject(1, matchId);
            statement.setObject(2, playerId);
            statement.executeUpdate();
        }
    }

    private void rawCompleteWithoutResult(UUID matchId, UUID winner) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ranked_matches
                    SET status = 'COMPLETED',
                        winner_player_id = ?,
                        result_operation_id = ?,
                        finished_at = NOW(),
                        state_version = state_version + 1
                    WHERE match_id = ?
                    """)) {
                statement.setObject(1, winner);
                statement.setObject(2, UUID.randomUUID());
                statement.setObject(3, matchId);
                assertEquals(1, statement.executeUpdate());
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
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
}
