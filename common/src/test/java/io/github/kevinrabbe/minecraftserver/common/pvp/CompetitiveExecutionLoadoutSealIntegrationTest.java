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
class CompetitiveExecutionLoadoutSealIntegrationTest {
    private static final String BACKEND = "legacy-loadout-seal";
    private static final Duration LEASE = Duration.ofMinutes(2);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private ClanWarLifecycleRepository wars;
    private ClanWarLoadoutReadinessRepository readiness;
    private RankedArenaRepository ranked;
    private CompetitiveExecutionRepository executions;
    private BackendRegistry backends;

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
    void clanWarSnapshotSealsEvenWhenFinalSelectionIsEmptyAndRejectsLaterAppend() throws Exception {
        UUID leaderA = player("SealWarA");
        UUID leaderB = player("SealWarB");
        ClanSnapshot clanA = memberships.createClan(UUID.randomUUID(), leaderA, "Seal Alpha", randomTag());
        ClanSnapshot clanB = memberships.createClan(UUID.randomUUID(), leaderB, "Seal Beta", randomTag());
        ClanWarSnapshot war = wars.challenge(UUID.randomUUID(), leaderA, clanA.clanId(), clanB.clanId());
        wars.accept(UUID.randomUUID(), war.warId(), leaderB);
        wars.setRoster(UUID.randomUUID(), war.warId(), leaderA, clanA.clanId(), List.of(leaderA));
        wars.setRoster(UUID.randomUUID(), war.warId(), leaderB, clanB.clanId(), List.of(leaderB));
        wars.lockRoster(UUID.randomUUID(), war.warId());
        readiness.confirm(UUID.randomUUID(), war.warId(), leaderA);
        readiness.confirm(UUID.randomUUID(), war.warId(), leaderB);

        CompetitiveExecutionSnapshot execution = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.CLAN_WAR, war.warId(), BACKEND, LEASE
        );

        assertEquals(1, sealCount(execution.executionId()));
        assertEquals(0, loadoutCount(execution.executionId()));
        assertThrows(SQLException.class, () -> appendLoadoutRow(execution.executionId(), 0));
        assertThrows(SQLException.class, () -> mutateSeal(execution.executionId()));
    }

    @Test
    void rankedExecutionCannotReceiveClanWarLoadoutRows() throws Exception {
        UUID playerA = player("SealRankA");
        UUID playerB = player("SealRankB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot execution = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND, LEASE
        );

        assertEquals(0, sealCount(execution.executionId()));
        assertThrows(SQLException.class, () -> appendLoadoutRow(execution.executionId(), 0));
    }

    private int sealCount(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM competitive_execution_loadout_seals WHERE execution_id = ?"
             )) {
            statement.setObject(1, executionId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private int loadoutCount(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM competitive_execution_loadout_items WHERE execution_id = ?"
             )) {
            statement.setObject(1, executionId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private void appendLoadoutRow(UUID executionId, int participantIndex) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO competitive_execution_loadout_items(
                         execution_id,
                         participant_index,
                         loadout_item_index,
                         definition_id,
                         roll_state,
                         upgrade_level
                     ) VALUES (?, ?, 0, 'test.appended', '{}'::jsonb, 0)
                     """)) {
            statement.setObject(1, executionId);
            statement.setInt(2, participantIndex);
            statement.executeUpdate();
        }
    }

    private void mutateSeal(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE competitive_execution_loadout_seals
                     SET sealed_at = sealed_at + INTERVAL '1 second'
                     WHERE execution_id = ?
                     """)) {
            statement.setObject(1, executionId);
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
