package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceStatus;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneRoute;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneRouter;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerZoneRoutingRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveProxyRoundTripIntegrationTest {
    private static final String LEGACY_BACKEND = "legacy-round-trip";
    private static final String MODERN_BACKEND = "paper-round-trip";
    private static final String RETURN_ZONE = "city";
    private static final Duration FRESHNESS = Duration.ofMinutes(1);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerZoneRoutingRepository playerZones;
    private BackendRegistry backends;
    private ZoneInstanceRegistry zoneInstances;
    private ZoneRouter zoneRouter;
    private RankedArenaRepository ranked;
    private CompetitiveExecutionRepository executions;
    private CompetitiveExecutionService executionService;
    private CompetitiveDispatchRepository dispatchRepository;
    private CompetitiveDispatchService dispatchService;
    private CompetitiveEntryRouteRepository competitiveRoutes;
    private CompetitiveRuntimeTopologyRepository runtimeTopology;

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
        playerZones = new PlayerZoneRoutingRepository(dataSource);
        backends = new BackendRegistry(dataSource);
        zoneInstances = new ZoneInstanceRegistry(dataSource);
        zoneRouter = new ZoneRouter(dataSource, FRESHNESS);
        ranked = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        executions = new CompetitiveExecutionRepository(dataSource, FRESHNESS, Duration.ofMinutes(5));
        executionService = new CompetitiveExecutionService(
                executions,
                ranked,
                new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1()),
                new ClanWarResolutionRepository(dataSource)
        );
        dispatchRepository = new CompetitiveDispatchRepository(dataSource, executions, FRESHNESS, LEASE);
        dispatchService = new CompetitiveDispatchService(dispatchRepository, executionService, LEASE);
        competitiveRoutes = new CompetitiveEntryRouteRepository(dataSource, FRESHNESS);
        runtimeTopology = new CompetitiveRuntimeTopologyRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
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
                        transfer_tickets,
                        processed_operations,
                        player_sessions,
                        zone_instances,
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
        UUID instanceId = UUID.randomUUID();
        zoneInstances.registerStarting(instanceId, RETURN_ZONE, "v1", MODERN_BACKEND, 20, 25);
        zoneInstances.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, 0);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void activeCompetitiveRouteTransitionsBackToPersistedModernZoneAfterClosure() throws Exception {
        Player playerA = player("RoundTripA", new byte[]{1, 2, 3, 4});
        Player playerB = player("RoundTripB", new byte[]{5, 6, 7, 8});
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());

        CompetitiveExecutionSnapshot active = dispatchService.dispatchCandidate(
                candidate(CompetitiveActivityKind.RANKED_ARENA, match.matchId())
        ).orElseThrow();
        assertEquals(CompetitiveExecutionStatus.ACTIVE, active.status());

        List<CompetitiveEntryRoute> activeRoutes = competitiveRoutes.findAllActive();
        assertEquals(2, activeRoutes.size());
        assertTrue(activeRoutes.stream().allMatch(route -> route.backendId().equals(LEGACY_BACKEND)));
        assertTrue(activeRoutes.stream().anyMatch(route -> route.minecraftUuid().equals(playerA.minecraftUuid())));
        assertEquals(java.util.Set.of(LEGACY_BACKEND), runtimeTopology.findBackendIds());

        CompetitiveResultReportSnapshot report = executions.submitFailureReport(
                UUID.randomUUID(),
                active.executionId(),
                LEGACY_BACKEND
        );
        executionService.processReport(report.reportId());

        assertEquals(CompetitiveExecutionStatus.CLOSED, executions.load(active.executionId()).orElseThrow().status());
        assertTrue(competitiveRoutes.findAllActive().isEmpty());
        assertEquals(RETURN_ZONE, playerZones.findLogicalZone(playerA.minecraftUuid()).orElseThrow());

        ZoneRoute returnRoute = zoneRouter.findPreferredActiveInstance(RETURN_ZONE).orElseThrow();
        assertEquals(MODERN_BACKEND, returnRoute.backendId());
        assertEquals(RETURN_ZONE, returnRoute.zoneId());
        assertArrayEquals(playerA.payload(), loadStatePayload(playerA.playerId()));
    }

    private CompetitiveDispatchCandidate candidate(CompetitiveActivityKind kind, UUID activityId) throws SQLException {
        return dispatchRepository.listReadyActivities(100).stream()
                .filter(value -> value.activityKind() == kind && value.activityId().equals(activityId))
                .findFirst()
                .orElseThrow();
    }

    private Player player(String name, byte[] payload) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, name);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_state
                     SET logical_zone_id = ?, state_payload = ?
                     WHERE player_id = ?
                     """)) {
            statement.setString(1, RETURN_ZONE);
            statement.setBytes(2, payload);
            statement.setObject(3, playerId);
            assertEquals(1, statement.executeUpdate());
        }
        return new Player(playerId, minecraftUuid, payload.clone());
    }

    private byte[] loadStatePayload(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT state_payload
                     FROM player_state
                     WHERE player_id = ?
                     """)) {
            statement.setObject(1, playerId);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getBytes("state_payload");
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
                     ) VALUES ('round-trip-runtime', ?, 120, TRUE, 4)
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

    private record Player(UUID playerId, UUID minecraftUuid, byte[] payload) {
        private Player {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
