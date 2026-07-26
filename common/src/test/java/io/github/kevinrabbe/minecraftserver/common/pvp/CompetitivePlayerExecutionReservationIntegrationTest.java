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
import java.sql.ResultSet;
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
class CompetitivePlayerExecutionReservationIntegrationTest {
    private static final String BACKEND = "legacy-player-reservation";
    private static final Duration FRESHNESS = Duration.ofMinutes(1);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private BackendRegistry backends;
    private RankedArenaRepository ranked;
    private ClanWarLifecycleRepository wars;
    private CompetitiveExecutionRepository executions;
    private CompetitiveExecutionService executionService;
    private CompetitiveDispatchRepository dispatchRepository;
    private CompetitiveDispatchService dispatchService;

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
        executions = new CompetitiveExecutionRepository(dataSource, FRESHNESS, Duration.ofMinutes(5));
        executionService = new CompetitiveExecutionService(
                executions,
                ranked,
                wars,
                new ClanWarResolutionRepository(dataSource)
        );
        dispatchRepository = new CompetitiveDispatchRepository(dataSource, executions, FRESHNESS, LEASE);
        dispatchService = new CompetitiveDispatchService(dispatchRepository, executionService, LEASE);
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
        runtimePrincipal(2);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void assignmentReservesPlayersAndOnlyTerminalClosureReleasesThem() throws Exception {
        UUID playerA = player("ReserveA");
        UUID playerB = player("ReserveB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveDispatchCandidate candidate = candidate(CompetitiveActivityKind.RANKED_ARENA, match.matchId());

        CompetitiveExecutionSnapshot assigned = dispatchRepository.dispatch(UUID.randomUUID(), candidate).orElseThrow();
        assertEquals(CompetitiveExecutionStatus.ASSIGNED, assigned.status());
        assertEquals(2, reservationCount(assigned.executionId()));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM competitive_player_execution_reservations
                     WHERE execution_id = ?
                     """)) {
            statement.setObject(1, assigned.executionId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
        assertEquals(2, reservationCount(assigned.executionId()));

        CompetitiveExecutionSnapshot active = executionService.activate(assigned.executionId(), BACKEND, LEASE);
        CompetitiveResultReportSnapshot failure = executions.submitFailureReport(
                UUID.randomUUID(), active.executionId(), BACKEND
        );
        executionService.processReport(failure.reportId());

        assertEquals(RankedMatchStatus.CANCELLED, ranked.loadMatch(match.matchId()).orElseThrow().status());
        assertEquals(CompetitiveExecutionStatus.CLOSED, executions.load(active.executionId()).orElseThrow().status());
        assertEquals(0, reservationCount(active.executionId()));
    }

    @Test
    void rankedReservationDefersClanWarUntilPlayerIsReleased() throws Exception {
        UUID sharedPlayer = player("SharedPvP");
        UUID rankedOpponent = player("RankedOpp");
        UUID defenderLeader = player("WarDefender");

        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), sharedPlayer, "Shared Clan", randomTag()
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defenderLeader, "Defender Clan", randomTag()
        );
        ClanWarSnapshot war = wars.challenge(
                UUID.randomUUID(), sharedPlayer, challengerClan.clanId(), defenderClan.clanId()
        );
        wars.accept(UUID.randomUUID(), war.warId(), defenderLeader);
        wars.setRoster(
                UUID.randomUUID(), war.warId(), sharedPlayer, challengerClan.clanId(), List.of(sharedPlayer)
        );
        wars.setRoster(
                UUID.randomUUID(), war.warId(), defenderLeader, defenderClan.clanId(), List.of(defenderLeader)
        );
        wars.lockRoster(UUID.randomUUID(), war.warId());

        RankedMatchSnapshot rankedMatch = ranked.createMatch(UUID.randomUUID(), sharedPlayer, rankedOpponent);
        CompetitiveExecutionSnapshot rankedExecution = dispatchService.dispatchCandidate(
                candidate(CompetitiveActivityKind.RANKED_ARENA, rankedMatch.matchId())
        ).orElseThrow();
        assertEquals(CompetitiveExecutionStatus.ACTIVE, rankedExecution.status());
        assertEquals(2, reservationCount(rankedExecution.executionId()));

        CompetitiveDispatchCandidate warCandidate = candidate(CompetitiveActivityKind.CLAN_WAR, war.warId());
        assertTrue(
                dispatchService.dispatchCandidate(warCandidate).isEmpty(),
                "cross-category player reservation must defer the war rather than double-book the player"
        );
        assertEquals(0, executionCount(CompetitiveActivityKind.CLAN_WAR, war.warId()));

        CompetitiveResultReportSnapshot failure = executions.submitFailureReport(
                UUID.randomUUID(), rankedExecution.executionId(), BACKEND
        );
        executionService.processReport(failure.reportId());
        assertEquals(0, reservationCount(rankedExecution.executionId()));

        CompetitiveExecutionSnapshot warExecution = dispatchService.dispatchCandidate(warCandidate).orElseThrow();
        assertEquals(CompetitiveExecutionStatus.ACTIVE, warExecution.status());
        assertEquals(ClanWarStatus.ACTIVE, wars.loadWar(war.warId()).orElseThrow().status());
        assertEquals(2, reservationCount(warExecution.executionId()));
    }

    @Test
    void forgedReservationOutsideFrozenManifestIsRejected() throws Exception {
        UUID playerA = player("ManifestOnlyA");
        UUID playerB = player("ManifestOnlyB");
        UUID outsider = player("ManifestOut");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot assigned = dispatchRepository.dispatch(
                UUID.randomUUID(),
                candidate(CompetitiveActivityKind.RANKED_ARENA, match.matchId())
        ).orElseThrow();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO competitive_player_execution_reservations(player_id, execution_id)
                     VALUES (?, ?)
                     """)) {
            statement.setObject(1, outsider);
            statement.setObject(2, assigned.executionId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
        assertEquals(2, reservationCount(assigned.executionId()));
    }

    private CompetitiveDispatchCandidate candidate(CompetitiveActivityKind kind, UUID activityId) throws SQLException {
        return dispatchRepository.listReadyActivities(100).stream()
                .filter(value -> value.activityKind() == kind && value.activityId().equals(activityId))
                .findFirst()
                .orElseThrow();
    }

    private int reservationCount(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM competitive_player_execution_reservations
                     WHERE execution_id = ?
                     """)) {
            statement.setObject(1, executionId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private int executionCount(CompetitiveActivityKind kind, UUID activityId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM competitive_executions
                     WHERE activity_kind = ? AND activity_id = ?
                     """)) {
            statement.setString(1, kind.name());
            statement.setObject(2, activityId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
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
                         max_active_executions
                     ) VALUES ('reservation-runtime', ?, 120, TRUE, ?)
                     """)) {
            statement.setString(1, BACKEND);
            statement.setInt(2, capacity);
            statement.executeUpdate();
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
}
