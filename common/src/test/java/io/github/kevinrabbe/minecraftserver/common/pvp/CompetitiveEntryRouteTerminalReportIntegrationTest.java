package io.github.kevinrabbe.minecraftserver.common.pvp;

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
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveEntryRouteTerminalReportIntegrationTest {
    private static final String BACKEND = "legacy-route-terminal-report";
    private static final Duration FRESHNESS = Duration.ofMinutes(1);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private BackendRegistry backends;
    private RankedArenaRepository ranked;
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
        backends = new BackendRegistry(dataSource);
        RankedArenaRuleset ruleset = RankedArenaRuleset.legacy189V1();
        ranked = new RankedArenaRepository(dataSource, ruleset);
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
        backends.registerOnline(BACKEND, 0);
        runtimePrincipal();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void submittedTerminalReportMakesStillActiveExecutionImmediatelyNonRoutable() throws Exception {
        Player playerA = player("RouteTermA");
        Player playerB = player("RouteTermB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());

        CompetitiveDispatchCandidate candidate = dispatchRepository.listReadyActivities(10).stream()
                .filter(value -> value.activityKind() == CompetitiveActivityKind.RANKED_ARENA)
                .filter(value -> value.activityId().equals(match.matchId()))
                .findFirst()
                .orElseThrow();
        CompetitiveExecutionSnapshot active = dispatchService.dispatchCandidate(candidate).orElseThrow();

        assertEquals(CompetitiveExecutionStatus.ACTIVE, active.status());
        assertEquals(2, routes.findAllActive().size());
        assertTrue(routes.findByMinecraftUuid(playerA.minecraftUuid()).isPresent());

        CompetitiveResultReportSnapshot report = executions.submitFailureReport(
                UUID.randomUUID(),
                active.executionId(),
                BACKEND
        );

        assertEquals(CompetitiveReportStatus.PENDING, report.status());
        assertEquals(
                CompetitiveExecutionStatus.ACTIVE,
                executions.load(active.executionId()).orElseThrow().status(),
                "trusted settlement has deliberately not processed the report yet"
        );
        assertTrue(routes.findByMinecraftUuid(playerA.minecraftUuid()).isEmpty());
        assertTrue(routes.findByMinecraftUuid(playerB.minecraftUuid()).isEmpty());
        assertTrue(routes.findAllActive().isEmpty());
    }

    private Player player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        return new Player(identities.ensurePlayer(minecraftUuid, name), minecraftUuid);
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
                     ) VALUES ('route-terminal-runtime', ?, 120, TRUE, 4)
                     """)) {
            statement.setString(1, BACKEND);
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

    private record Player(UUID playerId, UUID minecraftUuid) {
    }
}
