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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapEncounterTransferAutoPinIntegrationTest {
    private static final String MAP_DEFINITION_ID = "map.autopin";
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
    private MapEncounterHandoffRepository handoffs;

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
                "Auto Pin Test Map",
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
        handoffs = new MapEncounterHandoffRepository(dataSource);
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
    void beginTransferAtomicallyPinsBoundMapReservationAndCreatesHandoff() throws Exception {
        UUID targetInstance = activeEncounterInstance();
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "AutoPinMap");
        SessionLease session = sessions.openSession(playerId, SOURCE_BACKEND, null, Duration.ofMinutes(5));
        MapItemProfile map = issueMap(playerId);
        UUID openOperationId = UUID.randomUUID();
        MapEncounterReservationSnapshot reserved = reservations.reserve(
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
                new byte[]{3, 9},
                "map.open"
        );

        TransferTicket ticket = sessions.beginTransfer(
                session.sessionId(),
                SOURCE_BACKEND,
                MAP_ZONE,
                opened.playerStateVersion(),
                Duration.ofSeconds(30)
        );

        TransferRow transfer = transfer(ticket.transferId());
        assertTrue(transfer.pinnedInstance());
        assertEquals(TARGET_BACKEND, transfer.targetBackendId());
        assertEquals(targetInstance, transfer.targetInstanceId());
        assertTrue(transfer.routed());
        MapEncounterHandoffSnapshot handoff = handoffs.findByRun(opened.runId()).orElseThrow();
        assertEquals(ticket.transferId(), handoff.transferId());
        assertEquals(reserved.reservationId(), handoff.reservationId());
        assertEquals(targetInstance, handoff.targetInstanceId());
    }

    @Test
    void normalTransferWithoutBoundMapReservationRemainsUnpinned() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "AutoPinNormal");
        SessionLease session = sessions.openSession(playerId, SOURCE_BACKEND, null, Duration.ofMinutes(5));

        TransferTicket ticket = sessions.beginTransfer(
                session.sessionId(),
                SOURCE_BACKEND,
                "starter_mine",
                session.stateVersion(),
                Duration.ofSeconds(30)
        );

        TransferRow transfer = transfer(ticket.transferId());
        assertFalse(transfer.pinnedInstance());
        assertNull(transfer.targetBackendId());
        assertNull(transfer.targetInstanceId());
        assertFalse(transfer.routed());
    }

    private MapItemProfile issueMap(UUID playerId) throws SQLException {
        return maps.issueMap(
                UUID.randomUUID(),
                MAP_DEFINITION_ID,
                playerId,
                new MapRunDefinition(
                        new MapDifficulty(25),
                        "forest",
                        "spider",
                        "extermination",
                        List.of("swarm"),
                        5678L,
                        1,
                        1,
                        "founding"
                ),
                "map.issue"
        );
    }

    private UUID activeEncounterInstance() throws SQLException {
        UUID instanceId = UUID.randomUUID();
        instances.registerStarting(instanceId, MAP_ZONE, MAP_TEMPLATE, TARGET_BACKEND, 1, 1);
        instances.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, 0);
        backends.heartbeat(TARGET_BACKEND, 0);
        return instanceId;
    }

    private TransferRow transfer(UUID transferId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT target_backend_id,
                            target_instance_id,
                            routed_at IS NOT NULL AS routed,
                            pinned_instance
                     FROM transfer_tickets
                     WHERE transfer_id = ?
                     """)) {
            statement.setObject(1, transferId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Unknown transfer: " + transferId);
                return new TransferRow(
                        row.getString("target_backend_id"),
                        row.getObject("target_instance_id", UUID.class),
                        row.getBoolean("routed"),
                        row.getBoolean("pinned_instance")
                );
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

    private record TransferRow(
            String targetBackendId,
            UUID targetInstanceId,
            boolean routed,
            boolean pinnedInstance
    ) { }
}
