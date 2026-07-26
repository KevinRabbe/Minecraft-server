package io.github.kevinrabbe.minecraftserver.competitivecontrol;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLifecycleRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarResolutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarRuleset;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveActivityKind;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionService;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveReportStatus;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveResultReportSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedArenaRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedArenaRuleset;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedMatchSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedMatchStatus;
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
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveControlWorkerIntegrationTest {
    private static final String BACKEND = "competitive-control-test";
    private static final Duration LEASE = Duration.ofMinutes(2);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private BackendRegistry backends;
    private RankedArenaRepository ranked;
    private CompetitiveExecutionRepository executions;
    private CompetitiveExecutionService service;
    private CompetitiveControlWorker worker;

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
        ranked = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        executions = new CompetitiveExecutionRepository(
                dataSource,
                Duration.ofMinutes(1),
                Duration.ofMinutes(5)
        );
        service = new CompetitiveExecutionService(
                executions,
                ranked,
                new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1()),
                new ClanWarResolutionRepository(dataSource)
        );
        Logger logger = Logger.getLogger(CompetitiveControlWorkerIntegrationTest.class.getName());
        logger.setLevel(Level.OFF);
        worker = new CompetitiveControlWorker(executions, service, 20, logger);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
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
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void onePoisonReportDoesNotBlockLaterValidSettlement() throws Exception {
        ReportFixture first = pendingRankedReport("PoisonA", "PoisonB");
        ReportFixture second = pendingRankedReport("HealthyA", "HealthyB");

        List<CompetitiveResultReportSnapshot> ordered = executions.listPendingReports(20);
        assertEquals(2, ordered.size());
        CompetitiveResultReportSnapshot poisonedReport = ordered.getFirst();
        CompetitiveResultReportSnapshot healthyReport = ordered.getLast();
        ReportFixture poisonedFixture = poisonedReport.reportId().equals(first.report().reportId()) ? first : second;
        ReportFixture healthyFixture = healthyReport.reportId().equals(first.report().reportId()) ? first : second;

        deleteRankedMatchOutOfBand(poisonedFixture.match().matchId());

        CompetitiveControlPassResult result = worker.runOnce();
        assertEquals(2, result.pendingReportsSeen());
        assertEquals(1, result.reportsApplied());
        assertEquals(1, result.reportFailures());
        assertEquals(0, result.expiredExecutionsSeen());
        assertEquals(0, result.executionsRecovered());
        assertEquals(0, result.recoveryFailures());

        assertEquals(
                CompetitiveReportStatus.PENDING,
                executions.loadReport(poisonedReport.reportId()).orElseThrow().status()
        );
        assertEquals(
                CompetitiveReportStatus.APPLIED,
                executions.loadReport(healthyReport.reportId()).orElseThrow().status()
        );
        RankedMatchSnapshot completed = ranked.loadMatch(healthyFixture.match().matchId()).orElseThrow();
        assertEquals(RankedMatchStatus.COMPLETED, completed.status());
        assertEquals(healthyFixture.winnerId(), completed.winnerPlayerId());
        assertTrue(executions.load(poisonedFixture.execution().executionId()).isPresent());
    }

    private ReportFixture pendingRankedReport(String nameA, String nameB) throws SQLException {
        UUID playerA = player(nameA);
        UUID playerB = player(nameB);
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(),
                CompetitiveActivityKind.RANKED_ARENA,
                match.matchId(),
                BACKEND,
                LEASE
        );
        CompetitiveExecutionSnapshot active = service.activate(assigned.executionId(), BACKEND, LEASE);
        CompetitiveResultReportSnapshot report = executions.submitWinnerReport(
                UUID.randomUUID(),
                active.executionId(),
                BACKEND,
                playerA
        );
        return new ReportFixture(match, active, report, playerA);
    }

    private void deleteRankedMatchOutOfBand(UUID matchId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM ranked_matches WHERE match_id = ?"
                )) {
                    statement.setObject(1, matchId);
                    assertEquals(1, statement.executeUpdate());
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record ReportFixture(
            RankedMatchSnapshot match,
            CompetitiveExecutionSnapshot execution,
            CompetitiveResultReportSnapshot report,
            UUID winnerId
    ) { }
}
