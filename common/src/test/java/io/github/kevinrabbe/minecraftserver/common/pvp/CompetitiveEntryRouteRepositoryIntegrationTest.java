package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveEntryRouteRepositoryIntegrationTest {
    private static final String BACKEND = "legacy-entry-route";
    private static final Duration FRESHNESS = Duration.ofMinutes(1);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private BackendRegistry backends;
    private RankedArenaRepository ranked;
    private ClanWarLifecycleRepository wars;
    private ClanWarLoadoutReadinessRepository warReadiness;
    private CompetitiveExecutionRepository executions;
    private CompetitiveExecutionService executionService;
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
        memberships = new ClanMembershipRepository(dataSource);
        backends = new BackendRegistry(dataSource);
        ranked = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        warReadiness = new ClanWarLoadoutReadinessRepository(dataSource);
        executions = new CompetitiveExecutionRepository(dataSource, FRESHNESS, Duration.ofMinutes(5));
        executionService = new CompetitiveExecutionService(
                executions,
                ranked,
                wars,
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
                        clan_war_loadout_confirmations,
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
                        clan_invitations,
                        clan_commodity_balances,
                        clan_treasuries,
                        clan_members,
                        clans,
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
        backends.registerOnline(BACKEND, 0);
        runtimePrincipal(4);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void assignedExecutionIsNotRoutableButActiveExecutionHasExactRankedRoute() throws Exception {
        Player playerA = player("RouteRankedA");
        Player playerB = player("RouteRankedB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());

        CompetitiveExecutionSnapshot assigned = dispatchRepository.dispatch(
                UUID.randomUUID(),
                candidate(CompetitiveActivityKind.RANKED_ARENA, match.matchId())
        ).orElseThrow();

        assertTrue(routes.findByMinecraftUuid(playerA.minecraftUuid()).isEmpty());

        CompetitiveExecutionSnapshot active = executionService.activate(assigned.executionId(), BACKEND, LEASE);
        CompetitiveEntryRoute route = routes.findByMinecraftUuid(playerA.minecraftUuid()).orElseThrow();

        assertEquals(active.executionId(), route.executionId());
        assertEquals(CompetitiveActivityKind.RANKED_ARENA, route.activityKind());
        assertEquals(match.matchId(), route.activityId());
        assertEquals(BACKEND, route.backendId());
        assertEquals(playerA.playerId(), route.playerId());
        assertEquals(playerA.minecraftUuid(), route.minecraftUuid());
        assertEquals("A", route.sideKey());
        assertEquals(playerA.playerId(), route.sideId());
        assertEquals(active.leaseExpiresAt(), route.leaseExpiresAt());
    }

    @Test
    void expiredOfflineAndStaleBackendsFailClosed() throws Exception {
        Player playerA = player("RouteHealthA");
        Player playerB = player("RouteHealthB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());
        CompetitiveExecutionSnapshot active = dispatchService.dispatchCandidate(
                candidate(CompetitiveActivityKind.RANKED_ARENA, match.matchId())
        ).orElseThrow();

        assertTrue(routes.findByMinecraftUuid(playerA.minecraftUuid()).isPresent());

        backends.markOffline(BACKEND);
        assertTrue(routes.findByMinecraftUuid(playerA.minecraftUuid()).isEmpty());

        backends.registerOnline(BACKEND, 0);
        staleBackendHeartbeat();
        assertTrue(routes.findByMinecraftUuid(playerA.minecraftUuid()).isEmpty());

        backends.heartbeat(BACKEND, 0);
        CompetitiveEntryRouteRepository afterLease = new CompetitiveEntryRouteRepository(
                dataSource,
                Duration.ofMinutes(10),
                Clock.fixed(Instant.now().plus(Duration.ofMinutes(2)), ZoneOffset.UTC)
        );
        assertTrue(afterLease.findByMinecraftUuid(playerA.minecraftUuid()).isEmpty());
        assertEquals(CompetitiveExecutionStatus.ACTIVE, active.status());
    }

    @Test
    void terminalClosureRemovesRoute() throws Exception {
        Player playerA = player("RouteCloseA");
        Player playerB = player("RouteCloseB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());
        CompetitiveExecutionSnapshot active = dispatchService.dispatchCandidate(
                candidate(CompetitiveActivityKind.RANKED_ARENA, match.matchId())
        ).orElseThrow();

        assertTrue(routes.findByMinecraftUuid(playerA.minecraftUuid()).isPresent());

        CompetitiveResultReportSnapshot failure = executions.submitFailureReport(
                UUID.randomUUID(), active.executionId(), BACKEND
        );
        executionService.processReport(failure.reportId());

        assertEquals(CompetitiveExecutionStatus.CLOSED, executions.load(active.executionId()).orElseThrow().status());
        assertTrue(routes.findByMinecraftUuid(playerA.minecraftUuid()).isEmpty());
    }

    @Test
    void clanWarRoutesResolveExactAuthoritativeSide() throws Exception {
        Player challenger = player("RouteWarA");
        Player defender = player("RouteWarB");
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), "Route Challenger", randomTag()
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), "Route Defender", randomTag()
        );
        ClanWarSnapshot war = wars.challenge(
                UUID.randomUUID(), challenger.playerId(), challengerClan.clanId(), defenderClan.clanId()
        );
        wars.accept(UUID.randomUUID(), war.warId(), defender.playerId());
        wars.setRoster(
                UUID.randomUUID(), war.warId(), challenger.playerId(), challengerClan.clanId(),
                List.of(challenger.playerId())
        );
        wars.setRoster(
                UUID.randomUUID(), war.warId(), defender.playerId(), defenderClan.clanId(),
                List.of(defender.playerId())
        );
        wars.lockRoster(UUID.randomUUID(), war.warId());
        warReadiness.confirm(UUID.randomUUID(), war.warId(), challenger.playerId());
        warReadiness.confirm(UUID.randomUUID(), war.warId(), defender.playerId());

        CompetitiveExecutionSnapshot active = dispatchService.dispatchCandidate(
                candidate(CompetitiveActivityKind.CLAN_WAR, war.warId())
        ).orElseThrow();
        assertEquals(CompetitiveExecutionStatus.ACTIVE, active.status());

        CompetitiveEntryRoute challengerRoute = routes.findByMinecraftUuid(challenger.minecraftUuid()).orElseThrow();
        assertEquals("CHALLENGER", challengerRoute.sideKey());
        assertEquals(challengerClan.clanId(), challengerRoute.sideId());
        assertEquals(challenger.playerId(), challengerRoute.playerId());

        CompetitiveEntryRoute defenderRoute = routes.findByMinecraftUuid(defender.minecraftUuid()).orElseThrow();
        assertEquals("DEFENDER", defenderRoute.sideKey());
        assertEquals(defenderClan.clanId(), defenderRoute.sideId());
        assertEquals(defender.playerId(), defenderRoute.playerId());
    }

    @Test
    void routeProjectionContainsOnlyRoutingAndIdentityFields() {
        assertEquals(
                List.of(
                        "executionId",
                        "activityKind",
                        "activityId",
                        "backendId",
                        "playerId",
                        "minecraftUuid",
                        "sideKey",
                        "sideId",
                        "leaseExpiresAt"
                ),
                Arrays.stream(CompetitiveEntryRoute.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList()
        );
    }

    private CompetitiveDispatchCandidate candidate(CompetitiveActivityKind kind, UUID activityId) throws SQLException {
        return dispatchRepository.listReadyActivities(100).stream()
                .filter(value -> value.activityKind() == kind && value.activityId().equals(activityId))
                .findFirst()
                .orElseThrow();
    }

    private Player player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        return new Player(identities.ensurePlayer(minecraftUuid, name), minecraftUuid);
    }

    private void staleBackendHeartbeat() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE backends
                     SET last_heartbeat_at = NOW() - INTERVAL '10 minutes'
                     WHERE backend_id = ?
                     """)) {
            statement.setString(1, BACKEND);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void runtimePrincipal(int capacity) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO competitive_runtime_principals(
                         database_role,
                         backend_id,
                         max_execution_lease_seconds,
                         dispatch_enabled,
                         max_active_executions,
                         supports_clan_war
                     ) VALUES ('entry-route-runtime', ?, 120, TRUE, ?, TRUE)
                     """)) {
            statement.setString(1, BACKEND);
            statement.setInt(2, capacity);
            statement.executeUpdate();
        }
    }

    private static String randomTag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record Player(UUID playerId, UUID minecraftUuid) {
    }
}
