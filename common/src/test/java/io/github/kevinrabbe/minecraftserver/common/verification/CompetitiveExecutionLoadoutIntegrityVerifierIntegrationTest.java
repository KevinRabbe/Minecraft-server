package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLifecycleRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLoadoutReadinessRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarRuleset;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveActivityKind;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.CompetitiveExecutionSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveExecutionLoadoutIntegrityVerifierIntegrationTest {
    private static final String BACKEND = "legacy-loadout-verifier";
    private static final Duration LEASE = Duration.ofMinutes(2);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private ClanWarLifecycleRepository wars;
    private ClanWarLoadoutReadinessRepository readiness;
    private CompetitiveExecutionRepository executions;
    private BackendRegistry backends;
    private CompetitiveExecutionLoadoutIntegrityVerifier verifier;

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
        executions = new CompetitiveExecutionRepository(dataSource, Duration.ofMinutes(1), Duration.ofMinutes(5));
        backends = new BackendRegistry(dataSource);
        verifier = new CompetitiveExecutionLoadoutIntegrityVerifier(dataSource);
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
    void healthyExplicitlyEmptySnapshotIsAcceptedAndAggregateVerifierIncludesIt() throws Exception {
        CompetitiveExecutionSnapshot execution = assignedEmptyWar("VerifyLoadoutA", "VerifyLoadoutB");

        assertTrue(verifier.verify(100).isEmpty());
        assertTrue(new PersistentIntegrityVerifier(dataSource).verify(100).isEmpty());
        assertTrue(execution.executionId() != null);
    }

    @Test
    void missingSealIsReported() throws Exception {
        CompetitiveExecutionSnapshot execution = assignedEmptyWar("VerifySealA", "VerifySealB");

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM competitive_execution_loadout_seals WHERE execution_id = ?"
            )) {
                statement.setObject(1, execution.executionId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("COMPETITIVE_LOADOUT_SEAL_MISMATCH")
                        && issue.subjectId().equals(execution.executionId().toString())));
    }

    @Test
    void appendedSnapshotValueWithoutCustodyIsReported() throws Exception {
        CompetitiveExecutionSnapshot execution = assignedEmptyWar("VerifyValueA", "VerifyValueB");

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO competitive_execution_loadout_items(
                        execution_id,
                        participant_index,
                        loadout_item_index,
                        definition_id,
                        roll_state,
                        upgrade_level
                    ) VALUES (?, 0, 0, 'verify.injected', '{"damage":5000}'::jsonb, 0)
                    """)) {
                statement.setObject(1, execution.executionId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("COMPETITIVE_LOADOUT_CUSTODY_MISMATCH")
                        && issue.subjectId().equals(execution.executionId() + ":0")));
    }

    @Test
    void nonContiguousSnapshotIndexesAreReportedAndOutputIsBounded() throws Exception {
        CompetitiveExecutionSnapshot first = assignedEmptyWar("VerifyGapA", "VerifyGapB");
        CompetitiveExecutionSnapshot second = assignedEmptyWar("VerifyGapC", "VerifyGapD");

        withReplicationTriggersDisabled(connection -> {
            insertGapRow(connection, first.executionId());
            insertGapRow(connection, second.executionId());
        });

        List<IntegrityIssue> issues = verifier.verify(1);
        assertEquals(1, issues.size());
        assertEquals("COMPETITIVE_LOADOUT_INDEX_MISMATCH", issues.getFirst().code());
    }

    private CompetitiveExecutionSnapshot assignedEmptyWar(String nameA, String nameB) throws SQLException {
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
        return executions.assign(
                UUID.randomUUID(),
                CompetitiveActivityKind.CLAN_WAR,
                war.warId(),
                BACKEND,
                LEASE
        );
    }

    private static void insertGapRow(Connection connection, UUID executionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO competitive_execution_loadout_items(
                    execution_id,
                    participant_index,
                    loadout_item_index,
                    definition_id,
                    roll_state,
                    upgrade_level
                ) VALUES (?, 0, 1, 'verify.gap', '{}'::jsonb, 0)
                """)) {
            statement.setObject(1, executionId);
            assertEquals(1, statement.executeUpdate());
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
