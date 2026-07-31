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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveRuntimeLoadoutSealBoundaryIntegrationTest {
    private static final String BACKEND = "legacy-runtime-seal";
    private static final String OTHER_BACKEND = "legacy-runtime-seal-other";
    private static final Duration LEASE = Duration.ofMinutes(2);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private ClanWarLifecycleRepository wars;
    private ClanWarLoadoutReadinessRepository readiness;
    private RankedArenaRepository ranked;
    private CompetitiveExecutionRepository executions;
    private CompetitiveExecutionService service;
    private BackendRegistry backends;
    private UUID runtimeIncarnation;

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
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        readiness = new ClanWarLoadoutReadinessRepository(dataSource);
        ranked = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        executions = new CompetitiveExecutionRepository(dataSource, Duration.ofMinutes(1), Duration.ofMinutes(5));
        service = new CompetitiveExecutionService(
                executions,
                ranked,
                wars,
                new ClanWarResolutionRepository(dataSource)
        );
        backends = new BackendRegistry(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        competitive_execution_loadout_seals,
                        competitive_execution_loadout_items,
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
            statement.execute("""
                    INSERT INTO competitive_runtime_principals(
                        database_role,
                        backend_id,
                        max_execution_lease_seconds,
                        dispatch_enabled,
                        max_active_executions
                    ) VALUES (SESSION_USER::TEXT, 'legacy-runtime-seal', 120, TRUE, 4)
                    """);
        }
        runtimeIncarnation = backends.registerOnline(BACKEND, 0);
        backends.registerOnline(OTHER_BACKEND, 0);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void sealedExplicitlyEmptySnapshotReturnsAnEmptyPage() throws Exception {
        CompetitiveExecutionSnapshot active = activeEmptyWar("RuntimeSealA", "RuntimeSealB", BACKEND);
        assertEquals(0, runtimeLoadoutRowCount(active.executionId()));
    }

    @Test
    void missingSealOnOwnedLiveWarFailsClosed() throws Exception {
        CompetitiveExecutionSnapshot active = activeEmptyWar("RuntimeSealOwnA", "RuntimeSealOwnB", BACKEND);
        deleteSeal(active.executionId());

        assertThrows(SQLException.class, () -> runtimeLoadoutRowCount(active.executionId()));
    }

    @Test
    void missingSealOnAnotherBackendRemainsIndistinguishableFromNotFound() throws Exception {
        CompetitiveExecutionSnapshot active = activeEmptyWar(
                "SealOtherA", "SealOtherB", OTHER_BACKEND
        );
        deleteSeal(active.executionId());

        assertEquals(0, runtimeLoadoutRowCount(active.executionId()));
    }

    @Test
    void rankedExecutionCannotBeGivenAClanWarLoadoutSeal() throws Exception {
        UUID playerA = player("RuntimeSealRankA");
        UUID playerB = player("RuntimeSealRankB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot execution = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND, LEASE
        );

        assertThrows(SQLException.class, () -> insertSeal(execution.executionId()));
    }

    private CompetitiveExecutionSnapshot activeEmptyWar(String nameA, String nameB, String backendId)
            throws SQLException {
        UUID leaderA = player(nameA);
        UUID leaderB = player(nameB);
        ClanSnapshot clanA = memberships.createClan(UUID.randomUUID(), leaderA, nameA + " Clan", randomTag());
        ClanSnapshot clanB = memberships.createClan(UUID.randomUUID(), leaderB, nameB + " Clan", randomTag());
        ClanWarSnapshot war = wars.challenge(UUID.randomUUID(), leaderA, clanA.clanId(), clanB.clanId());
        wars.accept(UUID.randomUUID(), war.warId(), leaderB);
        wars.setRoster(UUID.randomUUID(), war.warId(), leaderA, clanA.clanId(), List.of(leaderA));
        wars.setRoster(UUID.randomUUID(), war.warId(), leaderB, clanB.clanId(), List.of(leaderB));
        wars.lockRoster(UUID.randomUUID(), war.warId());
        readiness.confirm(UUID.randomUUID(), war.warId(), leaderA);
        readiness.confirm(UUID.randomUUID(), war.warId(), leaderB);

        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.CLAN_WAR, war.warId(), backendId, LEASE
        );
        return service.activate(assigned.executionId(), backendId, LEASE);
    }

    private int runtimeLoadoutRowCount(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM competitive_runtime_page_loadout(?, ?, NULL, NULL, 100)"
             )) {
            statement.setObject(1, runtimeIncarnation);
            statement.setObject(2, executionId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private void deleteSeal(UUID executionId) throws SQLException {
        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM competitive_execution_loadout_seals WHERE execution_id = ?"
            )) {
                statement.setObject(1, executionId);
                assertEquals(1, statement.executeUpdate());
            }
        });
    }

    private void insertSeal(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO competitive_execution_loadout_seals(execution_id) VALUES (?)"
             )) {
            statement.setObject(1, executionId);
            statement.executeUpdate();
        }
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
}
