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
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveExecutionServiceIntegrationTest {
    private static final Duration BACKEND_FRESHNESS = Duration.ofMinutes(1);
    private static final Duration MAX_LEASE = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final String BACKEND_A = "legacy-a";
    private static final String BACKEND_B = "legacy-b";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private BackendRegistry backends;
    private RankedArenaRepository ranked;
    private ClanWarLifecycleRepository wars;
    private ClanWarResolutionRepository warResolutions;
    private CompetitiveExecutionRepository executions;
    private CompetitiveExecutionService service;

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
        memberships = new ClanMembershipRepository(dataSource);
        backends = new BackendRegistry(dataSource);
        ranked = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        warResolutions = new ClanWarResolutionRepository(dataSource);
        executions = new CompetitiveExecutionRepository(dataSource, BACKEND_FRESHNESS, MAX_LEASE);
        service = new CompetitiveExecutionService(executions, ranked, wars, warResolutions);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
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
        backends.registerOnline(BACKEND_A, 0);
        backends.registerOnline(BACKEND_B, 0);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void rankedExecutionSettlesExactlyOnceFromTinyRuntimeReport() throws Exception {
        UUID playerA = player("ExecRankA");
        UUID playerB = player("ExecRankB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        UUID assignmentOperation = UUID.randomUUID();

        CompetitiveExecutionSnapshot assigned = executions.assign(
                assignmentOperation,
                CompetitiveActivityKind.RANKED_ARENA,
                match.matchId(),
                BACKEND_A,
                LEASE
        );
        assertEquals(assigned, executions.assign(
                assignmentOperation,
                CompetitiveActivityKind.RANKED_ARENA,
                match.matchId(),
                BACKEND_A,
                Duration.ofSeconds(30)
        ));
        assertEquals(CompetitiveExecutionStatus.ASSIGNED, assigned.status());

        CompetitiveExecutionSnapshot active = service.activate(assigned.executionId(), BACKEND_A, LEASE);
        assertEquals(CompetitiveExecutionStatus.ACTIVE, active.status());
        assertEquals(RankedMatchStatus.ACTIVE, ranked.loadMatch(match.matchId()).orElseThrow().status());
        assertEquals(active, service.activate(active.executionId(), BACKEND_A, LEASE));

        UUID reportOperation = UUID.randomUUID();
        CompetitiveResultReportSnapshot report = executions.submitWinnerReport(
                reportOperation,
                active.executionId(),
                BACKEND_A,
                playerA
        );
        assertEquals(report, executions.submitWinnerReport(
                reportOperation,
                active.executionId(),
                BACKEND_A,
                playerA
        ));
        assertThrows(
                CompetitiveExecutionException.class,
                () -> executions.submitWinnerReport(reportOperation, active.executionId(), BACKEND_A, playerB)
        );

        CompetitiveResultReportSnapshot applied = service.processReport(report.reportId());
        assertEquals(CompetitiveReportStatus.APPLIED, applied.status());
        assertEquals(applied, service.processReport(report.reportId()));
        RankedMatchSnapshot completed = ranked.loadMatch(match.matchId()).orElseThrow();
        assertEquals(RankedMatchStatus.COMPLETED, completed.status());
        assertEquals(playerA, completed.winnerPlayerId());
        assertEquals(1016, ranked.loadRating(playerA).orElseThrow().rating());
        assertEquals(984, ranked.loadRating(playerB).orElseThrow().rating());

        CompetitiveExecutionSnapshot closed = executions.load(active.executionId()).orElseThrow();
        assertEquals(CompetitiveExecutionStatus.CLOSED, closed.status());
        assertEquals(CompetitiveExecutionCloseReason.SETTLED, closed.closeReason());
        assertEquals(applied.settlementOperationId(), closed.settlementOperationId());
    }

    @Test
    void assignmentCannotDuplicateActivityOrActivateBeforeDurableStart() throws Exception {
        UUID playerA = player("ExecDupA");
        UUID playerB = player("ExecDupB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND_A, LEASE
        );

        assertThrows(
                CompetitiveExecutionException.class,
                () -> executions.assign(
                        UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND_B, LEASE
                )
        );
        assertThrows(
                SQLException.class,
                () -> executions.markActive(assigned.executionId(), BACKEND_A, assigned.stateVersion(), LEASE)
        );
    }

    @Test
    void reportRejectsWrongBackendAndExpiredLease() throws Exception {
        UUID playerA = player("ExecGuardA");
        UUID playerB = player("ExecGuardB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND_A, LEASE
        );
        CompetitiveExecutionSnapshot active = service.activate(assigned.executionId(), BACKEND_A, LEASE);

        assertThrows(
                CompetitiveExecutionException.class,
                () -> executions.submitWinnerReport(UUID.randomUUID(), active.executionId(), BACKEND_B, playerA)
        );
        expireExecution(active.executionId());
        assertThrows(
                CompetitiveExecutionException.class,
                () -> executions.submitWinnerReport(UUID.randomUUID(), active.executionId(), BACKEND_A, playerA)
        );
    }

    @Test
    void expiredAssignedRankedExecutionCancelsAndClosesFailed() throws Exception {
        UUID playerA = player("ExecExpireA");
        UUID playerB = player("ExecExpireB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND_A, LEASE
        );
        expireExecution(assigned.executionId());

        assertEquals(1, service.recoverExpired(10));
        assertEquals(RankedMatchStatus.CANCELLED, ranked.loadMatch(match.matchId()).orElseThrow().status());
        CompetitiveExecutionSnapshot closed = executions.load(assigned.executionId()).orElseThrow();
        assertEquals(CompetitiveExecutionStatus.CLOSED, closed.status());
        assertEquals(CompetitiveExecutionCloseReason.FAILED, closed.closeReason());
        assertEquals(0, service.recoverExpired(10));
    }

    @Test
    void crashGapWithStartedRankedActivityStillRecovers() throws Exception {
        UUID playerA = player("ExecCrashA");
        UUID playerB = player("ExecCrashB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND_A, LEASE
        );
        ranked.startMatch(UUID.randomUUID(), match.matchId());
        assertEquals(RankedMatchStatus.ACTIVE, ranked.loadMatch(match.matchId()).orElseThrow().status());
        expireExecution(assigned.executionId());

        assertEquals(1, service.recoverExpired(10));
        assertEquals(RankedMatchStatus.CANCELLED, ranked.loadMatch(match.matchId()).orElseThrow().status());
        assertEquals(
                CompetitiveExecutionCloseReason.FAILED,
                executions.load(assigned.executionId()).orElseThrow().closeReason()
        );
    }

    @Test
    void submittedReportIsSettledEvenAfterExecutionLeaseExpires() throws Exception {
        UUID playerA = player("ExecPendingA");
        UUID playerB = player("ExecPendingB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND_A, LEASE
        );
        CompetitiveExecutionSnapshot active = service.activate(assigned.executionId(), BACKEND_A, LEASE);
        CompetitiveResultReportSnapshot report = executions.submitWinnerReport(
                UUID.randomUUID(), active.executionId(), BACKEND_A, playerB
        );
        expireExecution(active.executionId());

        assertEquals(0, service.recoverExpired(10));
        assertEquals(1, service.processPending(10));
        assertEquals(RankedMatchStatus.COMPLETED, ranked.loadMatch(match.matchId()).orElseThrow().status());
        assertEquals(playerB, ranked.loadMatch(match.matchId()).orElseThrow().winnerPlayerId());
        assertEquals(CompetitiveReportStatus.APPLIED, executions.loadReport(report.reportId()).orElseThrow().status());
    }

    @Test
    void clanWarExecutionUsesSameLeaseAndReportBoundary() throws Exception {
        WarFixture fixture = lockedWar("ExecWarA", "ExecWarB");
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(),
                CompetitiveActivityKind.CLAN_WAR,
                fixture.war().warId(),
                BACKEND_A,
                LEASE
        );
        CompetitiveExecutionSnapshot active = service.activate(assigned.executionId(), BACKEND_A, LEASE);
        assertEquals(ClanWarStatus.ACTIVE, wars.loadWar(fixture.war().warId()).orElseThrow().status());

        CompetitiveResultReportSnapshot report = executions.submitWinnerReport(
                UUID.randomUUID(), active.executionId(), BACKEND_A, fixture.clanB().clanId()
        );
        service.processReport(report.reportId());

        ClanWarSnapshot completed = wars.loadWar(fixture.war().warId()).orElseThrow();
        assertEquals(ClanWarStatus.COMPLETED, completed.status());
        assertEquals(fixture.clanB().clanId(), completed.winningClanId());
        assertEquals(984, warResolutions.loadRating(fixture.clanA().clanId()).orElseThrow().rating());
        assertEquals(1016, warResolutions.loadRating(fixture.clanB().clanId()).orElseThrow().rating());
        assertEquals(
                CompetitiveExecutionCloseReason.SETTLED,
                executions.load(active.executionId()).orElseThrow().closeReason()
        );
    }

    @Test
    void expiredLockedClanWarFailsWithoutRuntimeSettlementAuthority() throws Exception {
        WarFixture fixture = lockedWar("ExecFailA", "ExecFailB");
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(),
                CompetitiveActivityKind.CLAN_WAR,
                fixture.war().warId(),
                BACKEND_A,
                LEASE
        );
        expireExecution(assigned.executionId());

        assertEquals(1, service.recoverExpired(10));
        assertEquals(ClanWarStatus.FAILED, wars.loadWar(fixture.war().warId()).orElseThrow().status());
        assertEquals(
                CompetitiveExecutionCloseReason.FAILED,
                executions.load(assigned.executionId()).orElseThrow().closeReason()
        );
    }

    @Test
    void pendingAndExpiredQueriesAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> executions.listPendingReports(0));
        assertThrows(IllegalArgumentException.class, () -> executions.listPendingReports(501));
        assertThrows(IllegalArgumentException.class, () -> executions.listExpiredExecutions(0));
        assertThrows(IllegalArgumentException.class, () -> executions.listExpiredExecutions(501));
    }

    private WarFixture lockedWar(String playerNameA, String playerNameB) throws SQLException {
        UUID leaderA = player(playerNameA);
        UUID leaderB = player(playerNameB);
        ClanSnapshot clanA = memberships.createClan(UUID.randomUUID(), leaderA, playerNameA + " Clan", randomTag());
        ClanSnapshot clanB = memberships.createClan(UUID.randomUUID(), leaderB, playerNameB + " Clan", randomTag());
        ClanWarSnapshot war = wars.challenge(UUID.randomUUID(), leaderA, clanA.clanId(), clanB.clanId());
        wars.accept(UUID.randomUUID(), war.warId(), leaderB);
        wars.setRoster(UUID.randomUUID(), war.warId(), leaderA, clanA.clanId(), List.of(leaderA));
        wars.setRoster(UUID.randomUUID(), war.warId(), leaderB, clanB.clanId(), List.of(leaderB));
        ClanWarSnapshot locked = wars.lockRoster(UUID.randomUUID(), war.warId());
        assertEquals(ClanWarStatus.ROSTER_LOCKED, locked.status());
        ClanWarLoadoutReadinessRepository readiness = new ClanWarLoadoutReadinessRepository(dataSource);
        readiness.confirm(UUID.randomUUID(), locked.warId(), leaderA);
        readiness.confirm(UUID.randomUUID(), locked.warId(), leaderB);
        return new WarFixture(clanA, clanB, locked);
    }

    private void expireExecution(UUID executionId) throws SQLException {
        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE competitive_executions
                    SET lease_expires_at = NOW() - INTERVAL '1 second'
                    WHERE execution_id = ?
                    """)) {
                statement.setObject(1, executionId);
                assertEquals(1, statement.executeUpdate());
            }
        });
    }

    private void withReplicationTriggersDisabled(SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                work.run(connection);
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

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    private record WarFixture(ClanSnapshot clanA, ClanSnapshot clanB, ClanWarSnapshot war) { }
}
