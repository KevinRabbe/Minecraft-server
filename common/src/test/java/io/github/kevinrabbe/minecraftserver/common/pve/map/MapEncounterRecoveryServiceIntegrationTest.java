package io.github.kevinrabbe.minecraftserver.common.pve.map;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceStatus;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import io.github.kevinrabbe.minecraftserver.common.session.TransferRecoveryRepository;
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
import java.sql.ResultSet;
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
class MapEncounterRecoveryServiceIntegrationTest {
    private static final String MAP_DEFINITION_ID = "map.recover_service";
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
    private MapEncounterRecoveryService recoveryService;

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
                MAP_DEFINITION_ID,
                "PAPER",
                "Recovery Service Map",
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
        recoveryService = new MapEncounterRecoveryService(
                new MapEncounterRecoveryRepository(dataSource),
                maps,
                new MapEncounterReservationReleaseRepository(dataSource),
                new TransferRecoveryRepository(dataSource),
                Duration.ZERO,
                Duration.ofMinutes(5),
                20
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        map_encounter_handoffs,
                        map_encounter_reservations,
                        map_open_player_state_evidence,
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
        insertEra("founding", 0, Instant.parse("2026-07-25T12:00:00Z"));
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void missingHandoffFailsRunAndReleasesBoundSlot() throws Exception {
        PreparedRun prepared = prepareRun("RecSvcNoHand");

        assertEquals(1, recoveryService.recoverOnce());
        assertEquals(MapRunStatus.FAILED, maps.loadRun(prepared.runId()).status());
        assertEquals(
                MapEncounterReservationStatus.RELEASED,
                reservations.load(prepared.reservationId()).status()
        );
        assertEquals(0, recoveryService.recoverOnce());
    }

    @Test
    void expiredPinnedTransferIsAbortedThenRunAndSlotAreRecovered() throws Exception {
        PreparedRun prepared = prepareRun("RecSvcExpire");
        TransferTicket ticket = sessions.beginTransfer(
                prepared.sessionId(),
                SOURCE_BACKEND,
                MAP_ZONE,
                prepared.playerStateVersion(),
                Duration.ofSeconds(30)
        );
        expireTransfer(ticket.transferId());

        assertEquals(1, recoveryService.recoverOnce());

        assertEquals(MapRunStatus.FAILED, maps.loadRun(prepared.runId()).status());
        assertEquals(
                MapEncounterReservationStatus.RELEASED,
                reservations.load(prepared.reservationId()).status()
        );
        assertEquals("ACTIVE", sessionStatus(prepared.sessionId()));
        assertTrue(ticketClosed(ticket.transferId()));
    }

    private PreparedRun prepareRun(String name) throws SQLException {
        activeEncounterInstance();
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, SOURCE_BACKEND, null, Duration.ofMinutes(5));
        MapItemProfile map = maps.issueMap(
                UUID.randomUUID(),
                MAP_DEFINITION_ID,
                playerId,
                new MapRunDefinition(
                        new MapDifficulty(20),
                        "forest",
                        "spider",
                        "extermination",
                        List.of("swarm"),
                        1357L,
                        1,
                        1,
                        "founding"
                ),
                "map.issue"
        );
        UUID operationId = UUID.randomUUID();
        MapEncounterReservationSnapshot reservation = reservations.reserve(
                operationId,
                playerId,
                map.itemInstanceId(),
                MAP_ZONE,
                MAP_TEMPLATE,
                Duration.ofSeconds(30)
        );
        MapPlayerStateOpenResult opened = statefulMaps.openMap(
                operationId,
                map.itemInstanceId(),
                session.sessionId(),
                SOURCE_BACKEND,
                session.stateVersion(),
                0,
                "city",
                "portal",
                new byte[]{1, 3, 5},
                "map.open"
        );
        return new PreparedRun(
                opened.runId(),
                reservation.reservationId(),
                session.sessionId(),
                opened.playerStateVersion()
        );
    }

    private void activeEncounterInstance() throws SQLException {
        UUID instanceId = UUID.randomUUID();
        instances.registerStarting(instanceId, MAP_ZONE, MAP_TEMPLATE, TARGET_BACKEND, 1, 1);
        instances.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, 0);
        backends.heartbeat(TARGET_BACKEND, 0);
    }

    private void expireTransfer(UUID transferId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE transfer_tickets
                     SET expires_at = NOW() - INTERVAL '1 second'
                     WHERE transfer_id = ?
                     """)) {
            statement.setObject(1, transferId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private String sessionStatus(UUID sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status
                     FROM player_sessions
                     WHERE network_session_id = ?
                     """)) {
            statement.setObject(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getString("status");
            }
        }
    }

    private boolean ticketClosed(UUID transferId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT consumed_at IS NOT NULL AS closed
                     FROM transfer_tickets
                     WHERE transfer_id = ?
                     """)) {
            statement.setObject(1, transferId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getBoolean("closed");
            }
        }
    }

    private void insertEra(String eraId, int sequence, Instant startedAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO world_eras(era_id, sequence_no, started_at)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setString(1, eraId);
            statement.setInt(2, sequence);
            statement.setTimestamp(3, Timestamp.from(startedAt));
            statement.executeUpdate();
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
            UUID runId,
            UUID reservationId,
            UUID sessionId,
            long playerStateVersion
    ) { }
}
