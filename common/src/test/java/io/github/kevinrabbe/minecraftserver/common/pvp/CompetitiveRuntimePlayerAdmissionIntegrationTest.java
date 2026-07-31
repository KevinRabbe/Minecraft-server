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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveRuntimePlayerAdmissionIntegrationTest {
    private static final String BACKEND = "legacy-player-admission";
    private static final String OTHER_BACKEND = "legacy-player-admission-other";

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
        }
        runtimeIncarnation = UUID.randomUUID();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void exactPlayerLookupReturnsOnlyItsActiveSanitizedManifest() throws Exception {
        mapCurrentLogin(BACKEND);
        assertEquals(BACKEND, runtimeRegister());

        PlayerRef playerA = player("AdmissionA");
        PlayerRef playerB = player("AdmissionB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(),
                CompetitiveActivityKind.RANKED_ARENA,
                match.matchId(),
                BACKEND,
                Duration.ofMinutes(2)
        );

        assertTrue(findPlayerExecution(playerA.minecraftUuid()).isEmpty(), "ASSIGNED execution must not admit players");

        CompetitiveExecutionSnapshot active = service.activate(
                assigned.executionId(),
                BACKEND,
                Duration.ofMinutes(2)
        );
        List<AdmissionRow> rows = findPlayerExecution(playerA.minecraftUuid());

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.executionId().equals(active.executionId())));
        assertEquals(List.of("A", "B"), rows.stream().map(AdmissionRow::sideKey).toList());
        assertEquals(List.of(playerA.minecraftUuid(), playerB.minecraftUuid()), rows.stream().map(AdmissionRow::minecraftUuid).toList());
        assertEquals("arena.legacy_1_8_9", rows.getFirst().rulesetId());
        assertEquals(1, rows.getFirst().teamSize());
        assertTrue(findPlayerExecution(UUID.randomUUID()).isEmpty());

        UUID reportId = submitFailure(active.executionId());
        assertTrue(findPlayerExecution(playerA.minecraftUuid()).isEmpty(), "reported execution must stop admitting immediately");
        assertEquals(CompetitiveReportStatus.PENDING, executions.loadReport(reportId).orElseThrow().status());
    }

    @Test
    void mappedRuntimeCannotAdmitPlayerFromAnotherBackend() throws Exception {
        mapCurrentLogin(BACKEND);
        runtimeRegister();
        backends.registerOnline(OTHER_BACKEND, 0);

        PlayerRef playerA = player("AdmissionOtherA");
        PlayerRef playerB = player("AdmissionOtherB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());
        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(),
                CompetitiveActivityKind.RANKED_ARENA,
                match.matchId(),
                OTHER_BACKEND,
                Duration.ofMinutes(2)
        );
        service.activate(assigned.executionId(), OTHER_BACKEND, Duration.ofMinutes(2));

        assertTrue(findPlayerExecution(playerA.minecraftUuid()).isEmpty());
    }

    @Test
    void admissionSurfaceContainsOnlySanitizedExecutionAndParticipantFields() throws Exception {
        mapCurrentLogin(BACKEND);
        runtimeRegister();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_find_player_execution(?)"
             )) {
            statement.setObject(1, UUID.randomUUID());
            try (ResultSet rows = statement.executeQuery()) {
                ResultSetMetaData metadata = rows.getMetaData();
                ArrayList<String> columns = new ArrayList<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    columns.add(metadata.getColumnLabel(index));
                }
                assertEquals(List.of(
                        "execution_id",
                        "activity_kind",
                        "activity_id",
                        "state_version",
                        "lease_expires_at",
                        "ruleset_id",
                        "ruleset_version",
                        "team_size",
                        "participant_index",
                        "side_key",
                        "side_id",
                        "player_id",
                        "minecraft_uuid",
                        "player_name"
                ), columns);
            }
        }
    }

    private List<AdmissionRow> findPlayerExecution(UUID minecraftUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_find_player_execution(?)"
             )) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<AdmissionRow> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new AdmissionRow(
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

    private void mapCurrentLogin(String backendId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO competitive_runtime_principals(
                         database_role,
                         backend_id,
                         max_execution_lease_seconds,
                         dispatch_enabled,
                         max_active_executions
                     ) VALUES (SESSION_USER::TEXT, ?, 120, TRUE, 4)
                     """)) {
            statement.setString(1, backendId);
            statement.executeUpdate();
        }
    }

    private String runtimeRegister() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_register(?, 0)"
             )) {
            statement.setObject(1, runtimeIncarnation);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private UUID submitFailure(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_submit_report(?, ?, 'FAILURE', NULL)"
             )) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, executionId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getObject(1, UUID.class);
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

    private record AdmissionRow(
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
}
