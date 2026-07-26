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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveDispatchRepositoryIntegrationTest {
    private static final String BACKEND_A = "legacy-dispatch-a";
    private static final String BACKEND_B = "legacy-dispatch-b";
    private static final Duration FRESHNESS = Duration.ofMinutes(1);
    private static final Duration LEASE = Duration.ofMinutes(2);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private BackendRegistry backends;
    private RankedArenaRepository ranked;
    private ClanWarLifecycleRepository wars;
    private ClanWarLoadoutReadinessRepository warReadiness;
    private CompetitiveExecutionRepository executions;
    private CompetitiveDispatchRepository dispatch;
    private CompetitiveRuntimeManifestRepository manifests;

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
        dispatch = new CompetitiveDispatchRepository(dataSource, executions, FRESHNESS, LEASE);
        manifests = new CompetitiveRuntimeManifestRepository(dataSource);
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
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void dispatchesReadyRankedToHealthyAllowlistedBackendAndReplays() throws Exception {
        backends.registerOnline(BACKEND_A, 0);
        backends.registerOnline(BACKEND_B, 4);
        principal("runtime-dispatch-a", BACKEND_A, true, 1);
        principal("runtime-dispatch-b", BACKEND_B, true, 1);

        RankedMatchSnapshot match = rankedMatch("DispatchA", "DispatchB");
        List<CompetitiveDispatchCandidate> ready = dispatch.listReadyActivities(10);
        assertEquals(1, ready.size());
        CompetitiveDispatchCandidate candidate = ready.getFirst();
        assertEquals(CompetitiveActivityKind.RANKED_ARENA, candidate.activityKind());
        assertEquals(match.matchId(), candidate.activityId());

        UUID operationId = UUID.randomUUID();
        CompetitiveExecutionSnapshot assigned = dispatch.dispatch(operationId, candidate).orElseThrow();
        assertEquals(BACKEND_A, assigned.backendId());
        assertEquals(CompetitiveExecutionStatus.ASSIGNED, assigned.status());
        assertEquals(assigned, dispatch.dispatch(operationId, candidate).orElseThrow());
        assertEquals(assigned, dispatch.dispatch(UUID.randomUUID(), candidate).orElseThrow());
        assertTrue(dispatch.listReadyActivities(10).isEmpty());

        CompetitiveRuntimeManifest manifest = manifests.load(assigned.executionId()).orElseThrow();
        assertEquals(match.matchId(), manifest.activityId());
        assertEquals(2, manifest.participants().size());
    }

    @Test
    void backendCapacityPreventsOversubscriptionAndSecondBackendTakesNextActivity() throws Exception {
        backends.registerOnline(BACKEND_A, 0);
        principal("runtime-cap-a", BACKEND_A, true, 1);
        RankedMatchSnapshot firstMatch = rankedMatch("CapacityA", "CapacityB");
        RankedMatchSnapshot secondMatch = rankedMatch("CapacityC", "CapacityD");

        CompetitiveDispatchCandidate first = candidate(firstMatch.matchId());
        CompetitiveDispatchCandidate second = candidate(secondMatch.matchId());
        CompetitiveExecutionSnapshot firstExecution = dispatch.dispatch(UUID.randomUUID(), first).orElseThrow();
        assertEquals(BACKEND_A, firstExecution.backendId());
        assertTrue(dispatch.dispatch(UUID.randomUUID(), second).isEmpty());

        backends.registerOnline(BACKEND_B, 0);
        principal("runtime-cap-b", BACKEND_B, true, 1);
        CompetitiveExecutionSnapshot secondExecution = dispatch.dispatch(UUID.randomUUID(), second).orElseThrow();
        assertEquals(BACKEND_B, secondExecution.backendId());
        assertFalse(firstExecution.executionId().equals(secondExecution.executionId()));
    }

    @Test
    void dispatchRejectsDisabledOfflineAndStaleBackends() throws Exception {
        RankedMatchSnapshot match = rankedMatch("GuardA", "GuardB");
        CompetitiveDispatchCandidate candidate = candidate(match.matchId());

        backends.registerOnline(BACKEND_A, 0);
        principal("runtime-guard-a", BACKEND_A, false, 1);
        assertTrue(dispatch.dispatch(UUID.randomUUID(), candidate).isEmpty());

        setDispatchEnabled(BACKEND_A, true);
        backends.markOffline(BACKEND_A);
        assertTrue(dispatch.dispatch(UUID.randomUUID(), candidate).isEmpty());

        backends.registerOnline(BACKEND_A, 0);
        staleBackend(BACKEND_A);
        assertTrue(dispatch.dispatch(UUID.randomUUID(), candidate).isEmpty());

        backends.heartbeat(BACKEND_A, 0);
        assertEquals(BACKEND_A, dispatch.dispatch(UUID.randomUUID(), candidate).orElseThrow().backendId());
    }

    @Test
    void assignmentOperationCannotBeReboundToAnotherActivity() throws Exception {
        backends.registerOnline(BACKEND_A, 0);
        principal("runtime-operation-a", BACKEND_A, true, 2);
        RankedMatchSnapshot first = rankedMatch("OperationA", "OperationB");
        RankedMatchSnapshot second = rankedMatch("OperationC", "OperationD");
        UUID operationId = UUID.randomUUID();

        dispatch.dispatch(operationId, candidate(first.matchId())).orElseThrow();
        assertThrows(
                SQLException.class,
                () -> dispatch.dispatch(operationId, candidate(second.matchId()))
        );
    }

    @Test
    void lockedClanWarUsesSameDispatchAndSanitizedManifestBoundary() throws Exception {
        backends.registerOnline(BACKEND_A, 0);
        principal("runtime-war-a", BACKEND_A, true, 1, true);

        UUID challengerLeader = player("WarDispatchA");
        UUID defenderLeader = player("WarDispatchB");
        ClanSnapshot challenger = memberships.createClan(
                UUID.randomUUID(), challengerLeader, "Dispatch Alpha", randomTag()
        );
        ClanSnapshot defender = memberships.createClan(
                UUID.randomUUID(), defenderLeader, "Dispatch Beta", randomTag()
        );
        ClanWarSnapshot war = wars.challenge(
                UUID.randomUUID(), challengerLeader, challenger.clanId(), defender.clanId()
        );
        wars.accept(UUID.randomUUID(), war.warId(), defenderLeader);
        wars.setRoster(
                UUID.randomUUID(), war.warId(), challengerLeader, challenger.clanId(), List.of(challengerLeader)
        );
        wars.setRoster(
                UUID.randomUUID(), war.warId(), defenderLeader, defender.clanId(), List.of(defenderLeader)
        );
        wars.lockRoster(UUID.randomUUID(), war.warId());
        warReadiness.confirm(UUID.randomUUID(), war.warId(), challengerLeader);
        warReadiness.confirm(UUID.randomUUID(), war.warId(), defenderLeader);

        CompetitiveDispatchCandidate candidate = dispatch.listReadyActivities(10).stream()
                .filter(value -> value.activityKind() == CompetitiveActivityKind.CLAN_WAR)
                .findFirst()
                .orElseThrow();
        CompetitiveExecutionSnapshot assigned = dispatch.dispatch(UUID.randomUUID(), candidate).orElseThrow();
        CompetitiveRuntimeManifest manifest = manifests.load(assigned.executionId()).orElseThrow();

        assertEquals(CompetitiveActivityKind.CLAN_WAR, manifest.activityKind());
        assertEquals(war.warId(), manifest.activityId());
        assertEquals(BACKEND_A, manifest.backendId());
        assertEquals(2, manifest.participants().size());
        assertEquals(1, manifest.teamSize());
    }

    @Test
    void readyQueryAndDurationsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> dispatch.listReadyActivities(0));
        assertThrows(IllegalArgumentException.class, () -> dispatch.listReadyActivities(501));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompetitiveDispatchRepository(dataSource, executions, Duration.ofMillis(500), LEASE)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompetitiveDispatchRepository(dataSource, executions, FRESHNESS, Duration.ofHours(2))
        );
    }

    private RankedMatchSnapshot rankedMatch(String playerAName, String playerBName) throws SQLException {
        return ranked.createMatch(UUID.randomUUID(), player(playerAName), player(playerBName));
    }

    private CompetitiveDispatchCandidate candidate(UUID activityId) throws SQLException {
        return dispatch.listReadyActivities(100).stream()
                .filter(value -> value.activityId().equals(activityId))
                .findFirst()
                .orElseThrow();
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void principal(String databaseRole, String backendId, boolean enabled, int capacity) throws SQLException {
        principal(databaseRole, backendId, enabled, capacity, false);
    }

    private void principal(
            String databaseRole,
            String backendId,
            boolean enabled,
            int capacity,
            boolean supportsClanWar
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO competitive_runtime_principals(
                         database_role,
                         backend_id,
                         max_execution_lease_seconds,
                         dispatch_enabled,
                         max_active_executions,
                         supports_ranked_arena,
                         supports_clan_war
                     ) VALUES (?, ?, 120, ?, ?, TRUE, ?)
                     """)) {
            statement.setString(1, databaseRole);
            statement.setString(2, backendId);
            statement.setBoolean(3, enabled);
            statement.setInt(4, capacity);
            statement.setBoolean(5, supportsClanWar);
            statement.executeUpdate();
        }
    }

    private void setDispatchEnabled(String backendId, boolean enabled) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE competitive_runtime_principals
                     SET dispatch_enabled = ?
                     WHERE backend_id = ?
                     """)) {
            statement.setBoolean(1, enabled);
            statement.setString(2, backendId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void staleBackend(String backendId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE backends
                     SET last_heartbeat_at = NOW() - INTERVAL '5 minutes'
                     WHERE backend_id = ?
                     """)) {
            statement.setString(1, backendId);
            assertEquals(1, statement.executeUpdate());
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
}
