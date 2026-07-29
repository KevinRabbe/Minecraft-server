package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceStatus;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationReleaseRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapEncounterReservationSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapItemProfile;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPlayerStateOpenRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPlayerStateOpenResult;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunSnapshot;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import io.github.kevinrabbe.minecraftserver.common.session.TransferTicket;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapEncounterIntegrityVerifierIntegrationTest {
    private static final String MAP_DEFINITION = "map.verify_encounter";
    private static final String SOURCE_BACKEND = "paper-source";
    private static final String TARGET_BACKEND = "paper-map";
    private static final String MAP_ZONE = "map_encounter";
    private static final String MAP_TEMPLATE = "v1";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private BackendRegistry backends;
    private ZoneInstanceRegistry instances;
    private MapAuthorityRepository maps;
    private MapPlayerStateOpenRepository statefulMaps;
    private MapEncounterReservationRepository reservations;
    private MapEncounterReservationReleaseRepository releases;
    private MapEncounterIntegrityVerifier verifier;

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
        sessions = new PlayerSessionRepository(dataSource);
        backends = new BackendRegistry(dataSource);
        instances = new ZoneInstanceRegistry(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                MAP_DEFINITION,
                "PAPER",
                "Verifier Encounter Map",
                1,
                ItemCategory.PROGRESSION,
                ItemIdentityKind.INDIVIDUAL
        )));
        maps = new MapAuthorityRepository(dataSource, catalog);
        statefulMaps = new MapPlayerStateOpenRepository(
                dataSource,
                catalog,
                (playerId, itemId, expectedItemVersion, currentPayload, nextPayload) -> { }
        );
        reservations = new MapEncounterReservationRepository(dataSource, Duration.ofSeconds(30));
        releases = new MapEncounterReservationReleaseRepository(dataSource);
        verifier = new MapEncounterIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        map_encounter_handoffs,
                        map_encounter_reservations,
                        map_open_player_state_evidence,
                        map_reward_grants,
                        map_reward_settlements,
                        map_clears,
                        map_run_participants,
                        map_runs,
                        map_item_profiles,
                        item_provenance,
                        item_instances,
                        processed_operations,
                        economic_ledger,
                        transfer_tickets,
                        player_sessions,
                        zone_instances,
                        backends,
                        player_state,
                        player_names,
                        wallets,
                        historical_events,
                        world_eras,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
        backends.registerOnline(SOURCE_BACKEND, 1);
        backends.registerOnline(TARGET_BACKEND, 0);
        insertEra();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void healthyStartedCompletedAndReleasedEncounterReconcilesCleanly() throws Exception {
        PreparedRun prepared = prepareBound("MapHealthy");
        beginPinnedTransfer(prepared);
        long startVersion = maps.loadRun(prepared.opened().runId()).stateVersion();
        maps.startRun(
                UUID.randomUUID(),
                prepared.opened().runId(),
                startVersion,
                List.of(prepared.playerId()),
                "map.start"
        );
        MapRunSnapshot active = maps.loadRun(prepared.opened().runId());
        maps.completeRun(
                UUID.randomUUID(),
                active.runId(),
                active.stateVersion(),
                15_000,
                "map.complete"
        );
        MapRunSnapshot completed = maps.loadRun(active.runId());

        // A terminal run may still be BOUND until the recovery/release pass executes.
        assertTrue(verifier.verify(100).isEmpty());

        releases.releaseTerminalRun(prepared.reservation().reservationId(), completed.runId());
        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void createdBoundRunBeforeHandoffIsRecoverableNotCorrupt() throws Exception {
        prepareBound("MapPreHandoff");

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void stateCoupledOpenVersionDriftIsReported() throws Exception {
        PreparedRun prepared = prepareBound("MapOpenState");

        corrupt(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE map_open_player_state_evidence
                    SET expected_player_state_version = expected_player_state_version + 1,
                        player_state_version = player_state_version + 1
                    WHERE open_operation_id = ?
                    """)) {
                statement.setObject(1, prepared.openOperationId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("MAP_OPEN_PLAYER_STATE_EVIDENCE_MISMATCH", prepared.openOperationId().toString());
    }

    @Test
    void reservationTargetSnapshotDriftIsReported() throws Exception {
        PreparedRun prepared = prepareBound("MapReserve");

        corrupt(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE map_encounter_reservations
                    SET target_backend_id = 'paper-wrong'
                    WHERE reservation_id = ?
                    """)) {
                statement.setObject(1, prepared.reservation().reservationId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("MAP_ENCOUNTER_RESERVATION_EVIDENCE_MISMATCH", prepared.reservation().reservationId().toString());
    }

    @Test
    void handoffTransferDriftIsReported() throws Exception {
        PreparedRun prepared = prepareBound("MapHandoff");
        TransferTicket ticket = beginPinnedTransfer(prepared);

        corrupt(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE transfer_tickets
                    SET pinned_instance = FALSE
                    WHERE transfer_id = ?
                    """)) {
                statement.setObject(1, ticket.transferId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("MAP_ENCOUNTER_HANDOFF_EVIDENCE_MISMATCH", prepared.opened().runId().toString());
    }

    @Test
    void startedReservedRunWithoutHandoffIsReported() throws Exception {
        PreparedRun prepared = prepareBound("MapNoHandoff");
        beginPinnedTransfer(prepared);
        maps.startRun(
                UUID.randomUUID(),
                prepared.opened().runId(),
                maps.loadRun(prepared.opened().runId()).stateVersion(),
                List.of(prepared.playerId()),
                "map.start"
        );
        MapRunSnapshot active = maps.loadRun(prepared.opened().runId());

        corrupt(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM map_encounter_handoffs WHERE run_id = ?")) {
                statement.setObject(1, active.runId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("MAP_ENCOUNTER_HANDOFF_EVIDENCE_MISMATCH", active.runId().toString());
    }

    @Test
    void terminalRunVersionDriftIsReported() throws Exception {
        PreparedRun prepared = prepareBound("MapTerminal");
        beginPinnedTransfer(prepared);
        maps.startRun(
                UUID.randomUUID(),
                prepared.opened().runId(),
                maps.loadRun(prepared.opened().runId()).stateVersion(),
                List.of(prepared.playerId()),
                "map.start"
        );
        MapRunSnapshot active = maps.loadRun(prepared.opened().runId());
        maps.completeRun(
                UUID.randomUUID(), active.runId(), active.stateVersion(), 12_000, "map.complete"
        );
        MapRunSnapshot completed = maps.loadRun(active.runId());

        corrupt(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE map_runs
                    SET terminal_expected_state_version = terminal_expected_state_version + 1
                    WHERE run_id = ?
                    """)) {
                statement.setObject(1, completed.runId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("MAP_RUN_LIFECYCLE_EVIDENCE_MISMATCH", completed.runId().toString());
    }

    @Test
    void failedBeforeStartCanReleaseWithoutHandoff() throws Exception {
        PreparedRun prepared = prepareBound("MapFailEarly");
        maps.failRun(
                UUID.randomUUID(),
                prepared.opened().runId(),
                maps.loadRun(prepared.opened().runId()).stateVersion(),
                "map.handoff_timeout"
        );
        MapRunSnapshot failed = maps.loadRun(prepared.opened().runId());
        releases.releaseTerminalRun(prepared.reservation().reservationId(), failed.runId());

        assertTrue(verifier.verify(100).isEmpty());
    }

    private PreparedRun prepareBound(String name) throws Exception {
        UUID targetInstanceId = activeEncounterInstance();
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, SOURCE_BACKEND, null, Duration.ofMinutes(5));
        MapItemProfile map = maps.issueMap(
                UUID.randomUUID(),
                MAP_DEFINITION,
                playerId,
                new MapRunDefinition(
                        new MapDifficulty(20),
                        "forest",
                        "spider",
                        "extermination",
                        List.of("swarm"),
                        9876L,
                        1,
                        1,
                        "founding"
                ),
                "map.issue"
        );
        UUID openOperationId = UUID.randomUUID();
        MapEncounterReservationSnapshot reservation = reservations.reserve(
                openOperationId,
                playerId,
                map.itemInstanceId(),
                MAP_ZONE,
                MAP_TEMPLATE,
                Duration.ofSeconds(30)
        );
        MapPlayerStateOpenResult opened = statefulMaps.openMap(
                openOperationId,
                map.itemInstanceId(),
                session.sessionId(),
                SOURCE_BACKEND,
                session.stateVersion(),
                0,
                "city",
                "portal",
                new byte[]{2, 6},
                "map.open"
        );
        return new PreparedRun(playerId, session, targetInstanceId, openOperationId, reservation, opened);
    }

    private TransferTicket beginPinnedTransfer(PreparedRun prepared) throws SQLException {
        return sessions.beginTransfer(
                prepared.session().sessionId(),
                SOURCE_BACKEND,
                MAP_ZONE,
                prepared.opened().playerStateVersion(),
                Duration.ofSeconds(30)
        );
    }

    private UUID activeEncounterInstance() throws SQLException {
        UUID instanceId = UUID.randomUUID();
        instances.registerStarting(instanceId, MAP_ZONE, MAP_TEMPLATE, TARGET_BACKEND, 1, 1);
        instances.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, 0);
        backends.heartbeat(TARGET_BACKEND, 0);
        return instanceId;
    }

    private void insertEra() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO world_eras(era_id, sequence_no, started_at)
                     VALUES ('founding', 0, ?)
                     """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.parse("2026-07-25T12:00:00Z")));
            statement.executeUpdate();
        }
    }

    private void assertIssue(String code, String subjectId) throws SQLException {
        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals(code) && issue.subjectId().equals(subjectId)));
    }

    private void corrupt(SqlWork work) throws SQLException {
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

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PreparedRun(
            UUID playerId,
            SessionLease session,
            UUID targetInstanceId,
            UUID openOperationId,
            MapEncounterReservationSnapshot reservation,
            MapPlayerStateOpenResult opened
    ) { }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }
}
