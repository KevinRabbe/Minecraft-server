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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveRuntimeTerminalHeartbeatIntegrationTest {
    private static final String BACKEND = "legacy-terminal-heartbeat";
    private static final Duration LEASE = Duration.ofMinutes(2);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private BackendRegistry backends;
    private RankedArenaRepository ranked;
    private CompetitiveExecutionRepository executions;
    private CompetitiveExecutionService service;
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
        backends = new BackendRegistry(dataSource);
        ranked = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        executions = new CompetitiveExecutionRepository(dataSource, Duration.ofMinutes(1), Duration.ofMinutes(5));
        service = new CompetitiveExecutionService(
                executions,
                ranked,
                new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1()),
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
                    ) VALUES (SESSION_USER::TEXT, 'legacy-terminal-heartbeat', 120, TRUE, 4)
                    """);
        }
        runtimeIncarnation = backends.registerOnline(BACKEND, 0);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void terminalReportImmediatelyMakesRuntimeLeaseNonRenewable() throws Exception {
        UUID playerA = identities.ensurePlayer(UUID.randomUUID(), "TerminalHeartA");
        UUID playerB = identities.ensurePlayer(UUID.randomUUID(), "TerminalHeartB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA, playerB);
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND, LEASE
        );
        CompetitiveExecutionSnapshot active = service.activate(assigned.executionId(), BACKEND, LEASE);

        RuntimeLease renewed = heartbeat(active.executionId(), active.stateVersion(), 30);
        assertEquals(active.stateVersion() + 1, renewed.stateVersion());

        submitWinner(active.executionId(), playerA);

        assertThrows(
                SQLException.class,
                () -> heartbeat(active.executionId(), renewed.stateVersion(), 30)
        );
    }

    private RuntimeLease heartbeat(UUID executionId, long expectedStateVersion, int seconds) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_heartbeat_execution(?, ?, ?, ?)"
             )) {
            statement.setObject(1, runtimeIncarnation);
            statement.setObject(2, executionId);
            statement.setLong(3, expectedStateVersion);
            statement.setInt(4, seconds);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return new RuntimeLease(row.getLong("state_version"));
            }
        }
    }

    private void submitWinner(UUID executionId, UUID winnerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_submit_report(?, ?, ?, 'WINNER', ?)"
             )) {
            statement.setObject(1, runtimeIncarnation);
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, executionId);
            statement.setObject(4, winnerId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
            }
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record RuntimeLease(long stateVersion) { }
}
