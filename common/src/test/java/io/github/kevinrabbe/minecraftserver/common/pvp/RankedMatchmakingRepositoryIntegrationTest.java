package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
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
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class RankedMatchmakingRepositoryIntegrationTest {
    private static final String LEGACY_BACKEND = "legacy-ranked-matchmaking";
    private static final String MODERN_BACKEND = "paper-ranked-matchmaking";
    private static final Duration FRESHNESS = Duration.ofMinutes(1);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private RankedArenaRepository ranked;
    private RankedMatchmakingRepository matchmaking;
    private BackendRegistry backends;
    private CompetitiveExecutionRepository executions;
    private CompetitiveDispatchRepository dispatchRepository;
    private CompetitiveDispatchService dispatchService;
    private CompetitiveEntryRouteRepository routes;

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
        sessions = new PlayerSessionRepository(dataSource);
        RankedArenaRuleset ruleset = RankedArenaRuleset.legacy189V1();
        ranked = new RankedArenaRepository(dataSource, ruleset);
        matchmaking = new RankedMatchmakingRepository(dataSource, ruleset);
        backends = new BackendRegistry(dataSource);
        executions = new CompetitiveExecutionRepository(dataSource, FRESHNESS, Duration.ofMinutes(5));
        CompetitiveExecutionService executionService = new CompetitiveExecutionService(
                executions,
                ranked,
                new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1()),
                new ClanWarResolutionRepository(dataSource)
        );
        dispatchRepository = new CompetitiveDispatchRepository(dataSource, executions, FRESHNESS, LEASE);
        dispatchService = new CompetitiveDispatchService(dispatchRepository, executionService, LEASE);
        routes = new CompetitiveEntryRouteRepository(dataSource, FRESHNESS);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        ranked_matchmaking_queue,
                        competitive_player_execution_reservations,
                        competitive_runtime_principals,
                        competitive_execution_participants,
                        competitive_execution_specs,
                        competitive_result_reports,
                        competitive_executions,
                        clan_war_results,
                        clan_war_items,
                        clan_war_rosters,
                        clan_wars,
                        clan_war_ratings,
                        ranked_match_results,
                        ranked_match_participants,
                        ranked_matches,
                        ranked_ratings,
                        processed_operations,
                        player_sessions,
                        player_state,
                        player_names,
                        wallets,
                        players,
                        backends
                    RESTART IDENTITY CASCADE
                    """);
        }
        backends.registerOnline(LEGACY_BACKEND, 0);
        backends.registerOnline(MODERN_BACKEND, 0);
        runtimePrincipal();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void secondOptInAtomicallyCreatesReadyMatchAndFeedsExistingDispatchPath() throws Exception {
        Player playerA = player("QueueA");
        Player playerB = player("QueueB");

        assertTrue(matchmaking.join(playerA.playerId()).isEmpty());
        assertTrue(matchmaking.queuedAt(playerA.playerId()).isPresent());

        RankedMatchSnapshot match = matchmaking.join(playerB.playerId()).orElseThrow();
        assertEquals(RankedMatchStatus.CREATED, match.status());
        assertEquals(playerA.playerId(), match.playerAId());
        assertEquals(playerB.playerId(), match.playerBId());
        assertTrue(matchmaking.queuedAt(playerA.playerId()).isEmpty());
        assertTrue(matchmaking.queuedAt(playerB.playerId()).isEmpty());
        assertEquals(1000, ranked.loadRating(playerA.playerId()).orElseThrow().rating());
        assertEquals(1000, ranked.loadRating(playerB.playerId()).orElseThrow().rating());

        assertEquals(match.matchId(), matchmaking.join(playerA.playerId()).orElseThrow().matchId());

        CompetitiveDispatchCandidate candidate = dispatchRepository.listReadyActivities(10).stream()
                .filter(value -> value.activityKind() == CompetitiveActivityKind.RANKED_ARENA)
                .filter(value -> value.activityId().equals(match.matchId()))
                .findFirst()
                .orElseThrow();
        CompetitiveExecutionSnapshot execution = dispatchService.dispatchCandidate(candidate).orElseThrow();

        assertEquals(CompetitiveExecutionStatus.ACTIVE, execution.status());
        assertEquals(LEGACY_BACKEND, execution.backendId());
        assertEquals(
                LEGACY_BACKEND,
                routes.findByMinecraftUuid(playerA.minecraftUuid()).orElseThrow().backendId()
        );
    }

    @Test
    void waitingPlayerCanLeaveBeforeAnyMatchExists() throws Exception {
        Player player = player("QueueLeave");

        assertTrue(matchmaking.join(player.playerId()).isEmpty());
        assertTrue(matchmaking.leave(player.playerId()));
        assertFalse(matchmaking.leave(player.playerId()));
        assertTrue(matchmaking.queuedAt(player.playerId()).isEmpty());
        assertTrue(matchmaking.liveMatch(player.playerId()).isEmpty());
    }

    @Test
    void trustedDirectMatchCreationConsumesStaleQueueIntent() throws Exception {
        Player queued = player("QueueDirectA");
        Player opponent = player("QueueDirectB");

        assertTrue(matchmaking.join(queued.playerId()).isEmpty());
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), queued.playerId(), opponent.playerId());

        assertEquals(RankedMatchStatus.CREATED, match.status());
        assertTrue(matchmaking.queuedAt(queued.playerId()).isEmpty());
        assertEquals(match.matchId(), matchmaking.liveMatch(queued.playerId()).orElseThrow().matchId());
    }

    @Test
    void oldestWaitingOpponentWinsTheFifoPairing() throws Exception {
        Player oldest = player("QueueOldest");
        Player newer = player("QueueNewer");
        Player joining = player("QueueJoining");

        insertQueue(oldest.playerId(), "10 seconds");
        insertQueue(newer.playerId(), "5 seconds");

        RankedMatchSnapshot match = matchmaking.join(joining.playerId()).orElseThrow();
        assertEquals(oldest.playerId(), match.playerAId());
        assertEquals(joining.playerId(), match.playerBId());
        assertTrue(matchmaking.queuedAt(oldest.playerId()).isEmpty());
        assertTrue(matchmaking.queuedAt(joining.playerId()).isEmpty());
        assertTrue(matchmaking.queuedAt(newer.playerId()).isPresent());
    }

    @Test
    void playerWithoutLivePersistentSessionCannotQueue() throws Exception {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, "QueueNoSession");

        RankedArenaException failure = assertThrows(RankedArenaException.class, () -> matchmaking.join(playerId));
        assertTrue(failure.getMessage().contains("live persistent session"));
        assertTrue(matchmaking.queuedAt(playerId).isEmpty());
    }

    @Test
    void explicitSessionDisconnectDeletesWaitingIntent() throws Exception {
        Player waiting = player("QueueDisconnect");
        assertTrue(matchmaking.join(waiting.playerId()).isEmpty());
        assertTrue(matchmaking.queuedAt(waiting.playerId()).isPresent());

        sessions.disconnect(waiting.sessionId(), MODERN_BACKEND);

        assertTrue(matchmaking.queuedAt(waiting.playerId()).isEmpty());
        assertFalse(rawQueueContains(waiting.playerId()));
    }

    @Test
    void expiredSessionQueueEntryIsPurgedBeforeAnotherPlayerCanPairWithIt() throws Exception {
        Player expired = player("QueueExpired");
        Player joining = player("QueuePostExpire");
        assertTrue(matchmaking.join(expired.playerId()).isEmpty());
        expireSession(expired.sessionId());

        assertTrue(matchmaking.queuedAt(expired.playerId()).isEmpty());
        assertTrue(matchmaking.join(joining.playerId()).isEmpty());
        assertFalse(rawQueueContains(expired.playerId()));
        assertTrue(matchmaking.queuedAt(joining.playerId()).isPresent());
        assertTrue(matchmaking.liveMatch(joining.playerId()).isEmpty());
    }

    private Player player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, name);
        SessionLease lease = sessions.openSession(playerId, MODERN_BACKEND, null, LEASE);
        return new Player(playerId, minecraftUuid, lease.sessionId());
    }

    private void insertQueue(UUID playerId, String age) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ranked_matchmaking_queue(player_id, joined_at)
                     VALUES (?, NOW() - (?::INTERVAL))
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, age);
            statement.executeUpdate();
        }
    }

    private void expireSession(UUID sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_sessions
                     SET lease_expires_at = NOW() - INTERVAL '1 second'
                     WHERE network_session_id = ?
                     """)) {
            statement.setObject(1, sessionId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private boolean rawQueueContains(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1
                     FROM ranked_matchmaking_queue
                     WHERE player_id = ?
                     """)) {
            statement.setObject(1, playerId);
            try (var row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private void runtimePrincipal() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO competitive_runtime_principals(
                         database_role,
                         backend_id,
                         max_execution_lease_seconds,
                         dispatch_enabled,
                         max_active_executions
                     ) VALUES ('ranked-matchmaking-runtime', ?, 120, TRUE, 4)
                     """)) {
            statement.setString(1, LEGACY_BACKEND);
            statement.executeUpdate();
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record Player(UUID playerId, UUID minecraftUuid, UUID sessionId) {
    }
}
