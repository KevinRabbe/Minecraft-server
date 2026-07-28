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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveRuntimeWinnerBindingIntegrationTest {
    private static final String BACKEND = "legacy-winner-binding";
    private static final Duration LEASE = Duration.ofMinutes(2);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private BackendRegistry backends;
    private ClanWarLifecycleRepository wars;
    private ClanWarLoadoutReadinessRepository readiness;
    private CompetitiveExecutionRepository executions;
    private CompetitiveExecutionService service;

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
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        readiness = new ClanWarLoadoutReadinessRepository(dataSource);
        executions = new CompetitiveExecutionRepository(dataSource, Duration.ofMinutes(1), Duration.ofMinutes(5));
        service = new CompetitiveExecutionService(
                executions,
                new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1()),
                wars,
                new ClanWarResolutionRepository(dataSource)
        );
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
                    ) VALUES (SESSION_USER::TEXT, 'legacy-winner-binding', 120, TRUE, 4)
                    """);
        }
        backends.registerOnline(BACKEND, 0);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void unsealedClanWarRejectsWinnerButStillAcceptsSafeFailure() throws Exception {
        UUID challenger = identities.ensurePlayer(UUID.randomUUID(), "WinnerBindA");
        UUID defender = identities.ensurePlayer(UUID.randomUUID(), "WinnerBindB");
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger, "Winner Bind A", randomTag()
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender, "Winner Bind B", randomTag()
        );
        ClanWarSnapshot war = wars.challenge(
                UUID.randomUUID(), challenger, challengerClan.clanId(), defenderClan.clanId()
        );
        wars.accept(UUID.randomUUID(), war.warId(), defender);
        wars.setRoster(UUID.randomUUID(), war.warId(), challenger, challengerClan.clanId(), List.of(challenger));
        wars.setRoster(UUID.randomUUID(), war.warId(), defender, defenderClan.clanId(), List.of(defender));
        wars.lockRoster(UUID.randomUUID(), war.warId());
        readiness.confirm(UUID.randomUUID(), war.warId(), challenger);
        readiness.confirm(UUID.randomUUID(), war.warId(), defender);

        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.CLAN_WAR, war.warId(), BACKEND, LEASE
        );
        CompetitiveExecutionSnapshot active = service.activate(assigned.executionId(), BACKEND, LEASE);
        deleteSealBypassingTriggers(active.executionId());

        assertThrows(
                SQLException.class,
                () -> submitReport(active.executionId(), "WINNER", challengerClan.clanId())
        );

        UUID failureReportId = submitReport(active.executionId(), "FAILURE", null);
        assertNotNull(failureReportId);
    }

    @Test
    void winnerMustBeAFrozenExecutionSide() throws Exception {
        RankedArenaRepository ranked = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        UUID playerA = identities.ensurePlayer(UUID.randomUUID(), "WinnerRankA");
        UUID playerB = identities.ensurePlayer(UUID.randomUUID(), "WinnerRankB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND, LEASE
        );
        CompetitiveExecutionSnapshot active = service.activate(assigned.executionId(), BACKEND, LEASE);

        assertThrows(
                SQLException.class,
                () -> submitReport(active.executionId(), "WINNER", UUID.randomUUID())
        );
        assertNotNull(submitReport(active.executionId(), "WINNER", playerA));
    }

    private UUID submitReport(UUID executionId, String kind, UUID winnerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_submit_report(?, ?, ?, ?)"
             )) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, executionId);
            statement.setString(3, kind);
            if (winnerId == null) statement.setNull(4, java.sql.Types.OTHER);
            else statement.setObject(4, winnerId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private void deleteSealBypassingTriggers(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM competitive_execution_loadout_seals WHERE execution_id = ?"
                )) {
                    statement.setObject(1, executionId);
                    assertEquals(1, statement.executeUpdate());
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
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
