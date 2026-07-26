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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class RankedLeaderboardRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private RankedArenaRepository arena;
    private RankedLeaderboardRepository leaderboard;

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
        RankedArenaRuleset ruleset = RankedArenaRuleset.legacy189V1();
        arena = new RankedArenaRepository(dataSource, ruleset);
        leaderboard = new RankedLeaderboardRepository(dataSource, ruleset);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        ranked_matchmaking_queue,
                        competitive_player_execution_reservations,
                        competitive_execution_participants,
                        competitive_execution_specs,
                        competitive_result_reports,
                        competitive_executions,
                        ranked_match_results,
                        ranked_match_participants,
                        ranked_matches,
                        ranked_ratings,
                        processed_operations,
                        player_sessions,
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
    void leaderboardUsesImmutableResultsForStatsPeakAndCurrentName() throws Exception {
        PlayerRef alpha = player("RankTopAlpha");
        PlayerRef beta = player("RankTopBeta");
        PlayerRef gamma = player("RankTopGamma");
        PlayerRef idleA = player("RankIdleA");
        PlayerRef idleB = player("RankIdleB");

        complete(alpha.playerId(), beta.playerId(), alpha.playerId());
        complete(alpha.playerId(), gamma.playerId(), alpha.playerId());
        complete(gamma.playerId(), beta.playerId(), gamma.playerId());

        RankedMatchSnapshot idle = arena.createMatch(UUID.randomUUID(), idleA.playerId(), idleB.playerId());
        arena.cancelMatch(UUID.randomUUID(), idle.matchId());

        identities.ensurePlayer(alpha.minecraftUuid(), "RankAlphaNew");

        List<RankedLeaderboardEntry> rows = leaderboard.top(10);
        assertEquals(3, rows.size());

        RankedLeaderboardEntry first = rows.get(0);
        assertEquals(1, first.position());
        assertEquals(alpha.playerId(), first.playerId());
        assertEquals("RankAlphaNew", first.playerName());
        assertEquals(arena.loadRating(alpha.playerId()).orElseThrow().rating(), first.rating());
        assertEquals(first.rating(), first.peakRating());
        assertEquals(2L, first.wins());
        assertEquals(0L, first.losses());
        assertEquals(2L, first.matchesPlayed());
        assertEquals("arena.legacy_1_8_9", first.rulesetId());
        assertEquals(1, first.rulesetVersion());
        assertEquals(1, first.ratingPolicyVersion());

        RankedLeaderboardEntry second = rows.get(1);
        assertEquals(gamma.playerId(), second.playerId());
        assertEquals(1L, second.wins());
        assertEquals(1L, second.losses());
        assertTrue(second.peakRating() >= second.rating());

        RankedLeaderboardEntry third = rows.get(2);
        assertEquals(beta.playerId(), third.playerId());
        assertEquals(0L, third.wins());
        assertEquals(2L, third.losses());
        assertEquals(1000, third.peakRating());

        assertTrue(rows.stream().noneMatch(row -> row.playerId().equals(idleA.playerId())));
        assertTrue(rows.stream().noneMatch(row -> row.playerId().equals(idleB.playerId())));
        assertEquals(2, leaderboard.top(2).size());
    }

    @Test
    void incompatibleFutureRulesetHistoryFailsClosedInsteadOfMixingLadders() throws Exception {
        PlayerRef playerA = player("RankV2A");
        PlayerRef playerB = player("RankV2B");
        RankedArenaRuleset futureRuleset = new RankedArenaRuleset("arena.legacy_1_8_9", 2, 2, 1000, 48);
        RankedArenaRepository futureArena = new RankedArenaRepository(dataSource, futureRuleset);

        RankedMatchSnapshot match = futureArena.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());
        futureArena.startMatch(UUID.randomUUID(), match.matchId());
        futureArena.completeMatch(UUID.randomUUID(), match.matchId(), playerA.playerId());

        RankedArenaException failure = assertThrows(RankedArenaException.class, () -> leaderboard.top(10));
        assertTrue(failure.getMessage().contains("explicit ladder migration"));
    }

    @Test
    void leaderboardLimitIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> leaderboard.top(0));
        assertThrows(IllegalArgumentException.class, () -> leaderboard.top(101));
    }

    private void complete(UUID playerA, UUID playerB, UUID winner) throws SQLException {
        RankedMatchSnapshot match = arena.createMatch(UUID.randomUUID(), playerA, playerB);
        arena.startMatch(UUID.randomUUID(), match.matchId());
        arena.completeMatch(UUID.randomUUID(), match.matchId(), winner);
    }

    private PlayerRef player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, name);
        return new PlayerRef(playerId, minecraftUuid);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerRef(UUID playerId, UUID minecraftUuid) {
    }
}
