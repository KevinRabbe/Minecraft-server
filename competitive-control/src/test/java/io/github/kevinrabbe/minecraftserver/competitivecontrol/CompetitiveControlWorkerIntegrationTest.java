package io.github.kevinrabbe.minecraftserver.competitivecontrol;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLifecycleRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLoadoutReadinessRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarPreparationRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarResolutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarRuleset;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarStatus;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveActivityKind;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveDispatchRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveDispatchService;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionService;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionStatus;
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
import java.sql.ResultSet;
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
    private static final Duration CONTROL_LEASE = Duration.ofSeconds(60);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private BackendRegistry backends;
    private RankedArenaRepository ranked;
    private ClanWarLifecycleRepository clanWars;
    private ClanWarLoadoutReadinessRepository warReadiness;
    private CompetitiveExecutionRepository executions;
    private CompetitiveExecutionService service;
    private CompetitiveDispatchRepository dispatchRepository;
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
        memberships = new ClanMembershipRepository(dataSource);
        backends = new BackendRegistry(dataSource);
        ranked = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        clanWars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        warReadiness = new ClanWarLoadoutReadinessRepository(dataSource);
        executions = new CompetitiveExecutionRepository(
                dataSource,
                Duration.ofMinutes(1),
                Duration.ofMinutes(5)
        );
        service = new CompetitiveExecutionService(
                executions,
                ranked,
                clanWars,
                new ClanWarResolutionRepository(dataSource)
        );
        dispatchRepository = new CompetitiveDispatchRepository(
                dataSource,
                executions,
                Duration.ofMinutes(1),
                CONTROL_LEASE
        );
        CompetitiveDispatchService dispatchService = new CompetitiveDispatchService(
                dispatchRepository,
                service,
                CONTROL_LEASE
        );
        Logger logger = Logger.getLogger(CompetitiveControlWorkerIntegrationTest.class.getName());
        logger.setLevel(Level.OFF);
        worker = new CompetitiveControlWorker(
                executions,
                service,
                new ClanWarPreparationRepository(dataSource),
                clanWars,
                dispatchRepository,
                dispatchService,
                20,
                logger
        );
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
        assertEquals(0, result.rosterLockCandidatesSeen());
        assertEquals(0, result.clanWarRostersLocked());
        assertEquals(0, result.rosterLockFailures());
        assertEquals(0, result.readyActivitiesSeen());
        assertEquals(0, result.executionsDispatched());
        assertEquals(0, result.dispatchDeferred());
        assertEquals(0, result.dispatchFailures());

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

    @Test
    void readyActivityIsDispatchedActivatedAndCapacityDefersNextMatch() throws Exception {
        runtimePrincipal(1);
        RankedMatchSnapshot first = ranked.createMatch(
                UUID.randomUUID(), player("DispatchOneA"), player("DispatchOneB")
        );
        RankedMatchSnapshot second = ranked.createMatch(
                UUID.randomUUID(), player("DispatchTwoA"), player("DispatchTwoB")
        );

        CompetitiveControlPassResult result = worker.runOnce();
        assertEquals(0, result.rosterLockCandidatesSeen());
        assertEquals(0, result.clanWarRostersLocked());
        assertEquals(0, result.rosterLockFailures());
        assertEquals(2, result.readyActivitiesSeen());
        assertEquals(1, result.executionsDispatched());
        assertEquals(1, result.dispatchDeferred());
        assertEquals(0, result.dispatchFailures());

        CompetitiveExecutionSnapshot execution = executionFor(CompetitiveActivityKind.RANKED_ARENA, first.matchId());
        RankedMatchSnapshot firstAfter = ranked.loadMatch(first.matchId()).orElseThrow();
        RankedMatchSnapshot secondAfter = ranked.loadMatch(second.matchId()).orElseThrow();
        if (firstAfter.status() == RankedMatchStatus.CREATED) {
            execution = executionFor(CompetitiveActivityKind.RANKED_ARENA, second.matchId());
            assertEquals(RankedMatchStatus.ACTIVE, secondAfter.status());
            assertEquals(RankedMatchStatus.CREATED, firstAfter.status());
        } else {
            assertEquals(RankedMatchStatus.ACTIVE, firstAfter.status());
            assertEquals(RankedMatchStatus.CREATED, secondAfter.status());
        }
        assertEquals(BACKEND, execution.backendId());
        assertEquals(CompetitiveExecutionStatus.ACTIVE, execution.status());
        assertEquals(1, dispatchRepository.listReadyActivities(20).size());
    }

    @Test
    void completeAcceptedClanWarIsLockedBeforeLoadoutReadinessCanDispatchIt() throws Exception {
        runtimePrincipal(1);
        UUID challengerLeader = player("ControlWarA");
        UUID defenderLeader = player("ControlWarB");
        ClanSnapshot challenger = memberships.createClan(
                UUID.randomUUID(), challengerLeader, "Control Alpha", randomTag()
        );
        ClanSnapshot defender = memberships.createClan(
                UUID.randomUUID(), defenderLeader, "Control Beta", randomTag()
        );
        ClanWarSnapshot challenged = clanWars.challenge(
                UUID.randomUUID(), challengerLeader, challenger.clanId(), defender.clanId()
        );
        ClanWarSnapshot accepted = clanWars.accept(UUID.randomUUID(), challenged.warId(), defenderLeader);
        clanWars.setRoster(
                UUID.randomUUID(), accepted.warId(), challengerLeader, challenger.clanId(), List.of(challengerLeader)
        );
        clanWars.setRoster(
                UUID.randomUUID(), accepted.warId(), defenderLeader, defender.clanId(), List.of(defenderLeader)
        );

        CompetitiveControlPassResult preparationPass = worker.runOnce();
        assertEquals(1, preparationPass.rosterLockCandidatesSeen());
        assertEquals(1, preparationPass.clanWarRostersLocked());
        assertEquals(0, preparationPass.rosterLockFailures());
        assertEquals(0, preparationPass.readyActivitiesSeen());
        assertEquals(0, preparationPass.executionsDispatched());
        assertEquals(
                ClanWarStatus.ROSTER_LOCKED,
                clanWars.loadWar(accepted.warId()).orElseThrow().status()
        );

        warReadiness.confirm(UUID.randomUUID(), accepted.warId(), challengerLeader);
        warReadiness.confirm(UUID.randomUUID(), accepted.warId(), defenderLeader);

        CompetitiveControlPassResult dispatchPass = worker.runOnce();
        assertEquals(0, dispatchPass.rosterLockCandidatesSeen());
        assertEquals(0, dispatchPass.clanWarRostersLocked());
        assertEquals(0, dispatchPass.rosterLockFailures());
        assertEquals(1, dispatchPass.readyActivitiesSeen());
        assertEquals(1, dispatchPass.executionsDispatched());
        assertEquals(0, dispatchPass.dispatchDeferred());
        assertEquals(0, dispatchPass.dispatchFailures());
        assertEquals(ClanWarStatus.ACTIVE, clanWars.loadWar(accepted.warId()).orElseThrow().status());

        CompetitiveExecutionSnapshot execution = executionFor(CompetitiveActivityKind.CLAN_WAR, accepted.warId());
        assertEquals(BACKEND, execution.backendId());
        assertEquals(CompetitiveExecutionStatus.ACTIVE, execution.status());
    }

    @Test
    void rosterLockOperationIdIsDeterministicAndWarScoped() {
        UUID warA = UUID.randomUUID();
        UUID warB = UUID.randomUUID();
        assertEquals(
                CompetitiveControlWorker.rosterLockOperationId(warA),
                CompetitiveControlWorker.rosterLockOperationId(warA)
        );
        assertTrue(!CompetitiveControlWorker.rosterLockOperationId(warA)
                .equals(CompetitiveControlWorker.rosterLockOperationId(warB)));
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

    private CompetitiveExecutionSnapshot executionFor(CompetitiveActivityKind kind, UUID activityId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT execution_id
                     FROM competitive_executions
                     WHERE activity_kind = ? AND activity_id = ?
                     """)) {
            statement.setString(1, kind.name());
            statement.setObject(2, activityId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("No competitive execution for " + kind + "/" + activityId);
                }
                return executions.load(row.getObject(1, UUID.class)).orElseThrow();
            }
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
                     ) VALUES ('competitive-control-runtime', ?, 120, TRUE, ?, TRUE)
                     """)) {
            statement.setString(1, BACKEND);
            statement.setInt(2, capacity);
            statement.executeUpdate();
        }
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

    private record ReportFixture(
            RankedMatchSnapshot match,
            CompetitiveExecutionSnapshot execution,
            CompetitiveResultReportSnapshot report,
            UUID winnerId
    ) { }
}
