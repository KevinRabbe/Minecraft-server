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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveRuntimeDatabaseApiIntegrationTest {
    private static final String BACKEND = "legacy-db-api";
    private static final String OTHER_BACKEND = "legacy-db-other";

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
        runtimeIncarnation = UUID.randomUUID();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void unmappedDatabaseLoginCannotUseRuntimeApi() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_register(?, 0)"
             )) {
            statement.setObject(1, runtimeIncarnation);
            assertThrows(SQLException.class, statement::executeQuery);
        }
    }

    @Test
    void mappedRuntimeCanOnlyOperateItsSanitizedExecutionSurface() throws Exception {
        mapCurrentLogin(BACKEND, 120);
        assertEquals(BACKEND, runtimeRegister(0));
        assertEquals(BACKEND, runtimeHeartbeat(0));
        assertEquals("ONLINE", backendStatus(BACKEND));

        PlayerRef playerA = player("RuntimeDbA");
        PlayerRef playerB = player("RuntimeDbB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(),
                CompetitiveActivityKind.RANKED_ARENA,
                match.matchId(),
                BACKEND,
                Duration.ofMinutes(2)
        );
        CompetitiveExecutionSnapshot active = service.activate(
                assigned.executionId(),
                BACKEND,
                Duration.ofMinutes(2)
        );

        List<RuntimeRow> rows = pollActive(10);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.executionId().equals(active.executionId())));
        assertEquals(List.of("A", "B"), rows.stream().map(RuntimeRow::sideKey).toList());
        assertEquals(List.of(playerA.minecraftUuid(), playerB.minecraftUuid()), rows.stream().map(RuntimeRow::minecraftUuid).toList());
        assertEquals("arena.legacy_1_8_9", rows.getFirst().rulesetId());
        assertEquals(1, rows.getFirst().teamSize());

        RuntimeLease renewed = heartbeatExecution(active.executionId(), active.stateVersion(), 30);
        assertEquals(active.stateVersion() + 1, renewed.stateVersion());
        assertTrue(renewed.leaseExpiresAtMillis() > active.leaseExpiresAt().toEpochMilli());

        UUID reportOperationId = UUID.randomUUID();
        UUID reportId = submitReport(reportOperationId, active.executionId(), "WINNER", playerA.playerId());
        assertEquals(reportId, submitReport(reportOperationId, active.executionId(), "WINNER", playerA.playerId()));
        assertThrows(
                SQLException.class,
                () -> submitReport(reportOperationId, active.executionId(), "WINNER", playerB.playerId())
        );
        assertTrue(pollActive(10).isEmpty(), "reported execution must disappear from runtime polling immediately");

        assertEquals(1, service.processPending(10));
        assertEquals(RankedMatchStatus.COMPLETED, ranked.loadMatch(match.matchId()).orElseThrow().status());
        assertEquals(playerA.playerId(), ranked.loadMatch(match.matchId()).orElseThrow().winnerPlayerId());
        assertEquals(CompetitiveReportStatus.APPLIED, executions.loadReport(reportId).orElseThrow().status());

        assertEquals(BACKEND, runtimeMarkOffline());
        assertEquals("OFFLINE", backendStatus(BACKEND));
    }

    @Test
    void replacementRuntimeIncarnationFencesOlderProcessWrites() throws Exception {
        mapCurrentLogin(BACKEND, 120);
        UUID oldIncarnation = UUID.randomUUID();
        UUID replacementIncarnation = UUID.randomUUID();

        assertEquals(BACKEND, runtimeRegister(oldIncarnation, 2));
        assertEquals(BACKEND, runtimeHeartbeat(oldIncarnation, 3));
        assertEquals(BACKEND, runtimeRegister(replacementIncarnation, 4));

        assertThrows(SQLException.class, () -> runtimeHeartbeat(oldIncarnation, 5));
        assertThrows(SQLException.class, () -> runtimeMarkOffline(oldIncarnation));
        assertEquals("ONLINE", backendStatus(BACKEND));

        assertEquals(BACKEND, runtimeHeartbeat(replacementIncarnation, 6));
        assertEquals(BACKEND, runtimeMarkOffline(replacementIncarnation));
        assertEquals("OFFLINE", backendStatus(BACKEND));
    }

    @Test
    void mappedRuntimeCannotReportForAnotherBackendExecution() throws Exception {
        mapCurrentLogin(BACKEND, 120);
        runtimeRegister(0);
        backends.registerOnline(OTHER_BACKEND, 0);

        PlayerRef playerA = player("RuntimeXAA");
        PlayerRef playerB = player("RuntimeXBB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(),
                CompetitiveActivityKind.RANKED_ARENA,
                match.matchId(),
                OTHER_BACKEND,
                Duration.ofMinutes(2)
        );
        CompetitiveExecutionSnapshot active = service.activate(
                assigned.executionId(), OTHER_BACKEND, Duration.ofMinutes(2)
        );

        assertThrows(
                SQLException.class,
                () -> submitReport(UUID.randomUUID(), active.executionId(), "WINNER", playerA.playerId())
        );
        assertTrue(pollActive(10).isEmpty(), "mapped backend must not see another backend's manifest");
    }

    @Test
    void runtimeLeaseExtensionIsPrincipalBoundAndCapped() throws Exception {
        mapCurrentLogin(BACKEND, 30);
        runtimeRegister(0);
        PlayerRef playerA = player("RuntimeCapA");
        PlayerRef playerB = player("RuntimeCapB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND, Duration.ofMinutes(1)
        );
        CompetitiveExecutionSnapshot active = service.activate(assigned.executionId(), BACKEND, Duration.ofMinutes(1));

        assertThrows(SQLException.class, () -> heartbeatExecution(active.executionId(), active.stateVersion(), 31));
        RuntimeLease renewed = heartbeatExecution(active.executionId(), active.stateVersion(), 30);
        assertEquals(active.stateVersion() + 1, renewed.stateVersion());
        assertThrows(SQLException.class, () -> heartbeatExecution(active.executionId(), active.stateVersion(), 30));
    }

    private void mapCurrentLogin(String backendId, int maxLeaseSeconds) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO competitive_runtime_principals(
                         database_role, backend_id, max_execution_lease_seconds
                     ) VALUES (SESSION_USER::TEXT, ?, ?)
                     """)) {
            statement.setString(1, backendId);
            statement.setInt(2, maxLeaseSeconds);
            statement.executeUpdate();
        }
    }

    private String runtimeRegister(int playerCount) throws SQLException {
        return runtimeRegister(runtimeIncarnation, playerCount);
    }

    private String runtimeRegister(UUID incarnationId, int playerCount) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_register(?, ?)"
             )) {
            statement.setObject(1, incarnationId);
            statement.setInt(2, playerCount);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private String runtimeHeartbeat(int playerCount) throws SQLException {
        return runtimeHeartbeat(runtimeIncarnation, playerCount);
    }

    private String runtimeHeartbeat(UUID incarnationId, int playerCount) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_heartbeat(?, ?)"
             )) {
            statement.setObject(1, incarnationId);
            statement.setInt(2, playerCount);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private String runtimeMarkOffline() throws SQLException {
        return runtimeMarkOffline(runtimeIncarnation);
    }

    private String runtimeMarkOffline(UUID incarnationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_mark_offline(?)"
             )) {
            statement.setObject(1, incarnationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private List<RuntimeRow> pollActive(int limit) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_poll_active(?)"
             )) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<RuntimeRow> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new RuntimeRow(
                            rows.getObject("execution_id", UUID.class),
                            rows.getString("ruleset_id"),
                            rows.getInt("team_size"),
                            rows.getInt("participant_index"),
                            rows.getString("side_key"),
                            rows.getObject("side_id", UUID.class),
                            rows.getObject("player_id", UUID.class),
                            rows.getObject("minecraft_uuid", UUID.class),
                            rows.getString("player_name")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private RuntimeLease heartbeatExecution(UUID executionId, long stateVersion, int seconds) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_heartbeat_execution(?, ?, ?)"
             )) {
            statement.setObject(1, executionId);
            statement.setLong(2, stateVersion);
            statement.setInt(3, seconds);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return new RuntimeLease(
                        row.getLong("state_version"),
                        row.getTimestamp("lease_expires_at").toInstant().toEpochMilli()
                );
            }
        }
    }

    private UUID submitReport(UUID operationId, UUID executionId, String kind, UUID winnerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_submit_report(?, ?, ?, ?)"
             )) {
            statement.setObject(1, operationId);
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

    private String backendStatus(String backendId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status FROM backends WHERE backend_id = ?"
             )) {
            statement.setString(1, backendId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private PlayerRef player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        return new PlayerRef(identities.ensurePlayer(minecraftUuid, name), minecraftUuid);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerRef(UUID playerId, UUID minecraftUuid) { }

    private record RuntimeRow(
            UUID executionId,
            String rulesetId,
            int teamSize,
            int participantIndex,
            String sideKey,
            UUID sideId,
            UUID playerId,
            UUID minecraftUuid,
            String playerName
    ) { }

    private record RuntimeLease(long stateVersion, long leaseExpiresAtMillis) { }
}
