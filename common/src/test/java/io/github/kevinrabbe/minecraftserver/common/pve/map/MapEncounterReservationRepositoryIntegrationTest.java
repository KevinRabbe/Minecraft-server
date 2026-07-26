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
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapEncounterReservationRepositoryIntegrationTest {
    private static final String MAP_DEFINITION_ID = "map.reserved";
    private static final String SOURCE_BACKEND = "paper-source";
    private static final String TARGET_BACKEND = "paper-map";
    private static final String MAP_ZONE = "map_encounter";
    private static final String MAP_TEMPLATE = "v1";
    private static final Duration SESSION_LEASE = Duration.ofMinutes(5);
    private static final byte[] NEXT_PAYLOAD = new byte[]{4, 2, 7};

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private BackendRegistry backends;
    private ZoneInstanceRegistry instances;
    private MapAuthorityRepository maps;
    private MapPlayerStateOpenRepository statefulMaps;
    private MapEncounterReservationRepository reservations;

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
        states = new PlayerStateRepository(dataSource);
        backends = new BackendRegistry(dataSource);
        instances = new ZoneInstanceRegistry(dataSource);
        ItemCatalog catalog = mapCatalog();
        maps = new MapAuthorityRepository(dataSource, catalog);
        statefulMaps = new MapPlayerStateOpenRepository(
                dataSource,
                catalog,
                (playerId, itemId, expectedItemVersion, currentPayload, nextPayload) -> {
                    assertEquals(0L, expectedItemVersion);
                    assertArrayEquals(NEXT_PAYLOAD, nextPayload);
                }
        );
        reservations = new MapEncounterReservationRepository(dataSource, Duration.ofSeconds(30));
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        map_encounter_reservations,
                        map_open_player_state_evidence,
                        map_clears,
                        map_run_participants,
                        map_runs,
                        map_item_profiles,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
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
    void stateCoupledOpenAtomicallyBindsExactReservationToCreatedRun() throws Exception {
        UUID targetInstance = activeEncounterInstance(0);
        PlayerContext player = player("MapReserveOpen");
        MapItemProfile map = issue(player.playerId(), 25, 11L);
        UUID openOperationId = UUID.randomUUID();

        MapEncounterReservationSnapshot firstReservation = reservations.reserve(
                openOperationId,
                player.playerId(),
                map.itemInstanceId(),
                MAP_ZONE,
                MAP_TEMPLATE,
                Duration.ofSeconds(30)
        );
        assertEquals(firstReservation, reservations.reserve(
                openOperationId,
                player.playerId(),
                map.itemInstanceId(),
                MAP_ZONE,
                MAP_TEMPLATE,
                Duration.ofSeconds(30)
        ));
        assertEquals(targetInstance, firstReservation.targetInstanceId());
        assertEquals(MapEncounterReservationStatus.RESERVED, firstReservation.status());

        MapPlayerStateOpenResult opened = open(player, map, openOperationId);
        MapEncounterReservationSnapshot bound = reservations.load(firstReservation.reservationId());

        assertEquals(MapEncounterReservationStatus.BOUND, bound.status());
        assertEquals(opened.runId(), bound.runId());
        assertEquals(openOperationId, bound.openOperationId());
        assertEquals(firstReservation.targetInstanceId(), bound.targetInstanceId());
        assertEquals(firstReservation.reservationId(), evidenceReservation(opened.runId()));
        assertEquals(MapRunStatus.CREATED, maps.loadRun(opened.runId()).status());
        assertEquals(1L, states.load(player.playerId()).stateVersion());
    }

    @Test
    void expiredReservationRollsBackMapItemPlayerStateAndRun() throws Exception {
        activeEncounterInstance(0);
        PlayerContext player = player("MapReserveExpire");
        MapItemProfile map = issue(player.playerId(), 30, 22L);
        UUID operationId = UUID.randomUUID();
        MapEncounterReservationSnapshot reservation = reservations.reserve(
                operationId,
                player.playerId(),
                map.itemInstanceId(),
                MAP_ZONE,
                MAP_TEMPLATE,
                Duration.ofMillis(5)
        );
        Thread.sleep(20L);

        assertThrows(SQLException.class, () -> open(player, map, operationId));

        assertEquals(MapEncounterReservationStatus.RESERVED, reservations.load(reservation.reservationId()).status());
        assertEquals(0L, states.load(player.playerId()).stateVersion());
        assertItemOwned(map.itemInstanceId(), player.playerId(), 0L);
        assertEquals(0L, count("map_runs"));
        assertEquals(0L, count("map_open_player_state_evidence"));
    }

    @Test
    void releasedReservationCannotConsumeMapWithReservedOperation() throws Exception {
        activeEncounterInstance(0);
        PlayerContext player = player("MapResRelease");
        MapItemProfile map = issue(player.playerId(), 35, 33L);
        UUID operationId = UUID.randomUUID();
        MapEncounterReservationSnapshot reservation = reservations.reserve(
                operationId,
                player.playerId(),
                map.itemInstanceId(),
                MAP_ZONE,
                MAP_TEMPLATE,
                Duration.ofSeconds(30)
        );
        assertEquals(
                MapEncounterReservationStatus.RELEASED,
                reservations.releaseReserved(reservation.reservationId(), player.playerId()).status()
        );

        assertThrows(SQLException.class, () -> open(player, map, operationId));
        assertItemOwned(map.itemInstanceId(), player.playerId(), 0L);
        assertEquals(0L, states.load(player.playerId()).stateVersion());
    }

    @Test
    void separateMapsReserveSeparateEncounterInstances() throws Exception {
        UUID firstTarget = activeEncounterInstance(0);
        UUID secondTarget = activeEncounterInstance(0);
        PlayerContext player = player("MapReserveSlots");
        MapItemProfile firstMap = issue(player.playerId(), 40, 44L);
        MapItemProfile secondMap = issue(player.playerId(), 45, 55L);

        MapEncounterReservationSnapshot first = reservations.reserve(
                UUID.randomUUID(), player.playerId(), firstMap.itemInstanceId(),
                MAP_ZONE, MAP_TEMPLATE, Duration.ofSeconds(30)
        );
        MapEncounterReservationSnapshot second = reservations.reserve(
                UUID.randomUUID(), player.playerId(), secondMap.itemInstanceId(),
                MAP_ZONE, MAP_TEMPLATE, Duration.ofSeconds(30)
        );

        assertNotEquals(first.targetInstanceId(), second.targetInstanceId());
        assertTrue(List.of(firstTarget, secondTarget).contains(first.targetInstanceId()));
        assertTrue(List.of(firstTarget, secondTarget).contains(second.targetInstanceId()));
    }

    private MapPlayerStateOpenResult open(PlayerContext player, MapItemProfile map, UUID operationId) throws SQLException {
        return statefulMaps.openMap(
                operationId,
                map.itemInstanceId(),
                player.session().sessionId(),
                SOURCE_BACKEND,
                player.session().stateVersion(),
                0,
                "city",
                "portal",
                NEXT_PAYLOAD,
                "map.open"
        );
    }

    private PlayerContext player(String name) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, SOURCE_BACKEND, null, SESSION_LEASE);
        return new PlayerContext(playerId, session);
    }

    private UUID activeEncounterInstance(int playerCount) throws SQLException {
        UUID instanceId = UUID.randomUUID();
        instances.registerStarting(instanceId, MAP_ZONE, MAP_TEMPLATE, TARGET_BACKEND, 1, 1);
        instances.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, playerCount);
        backends.heartbeat(TARGET_BACKEND, playerCount);
        return instanceId;
    }

    private MapItemProfile issue(UUID playerId, int difficulty, long seed) throws SQLException {
        return maps.issueMap(
                UUID.randomUUID(),
                MAP_DEFINITION_ID,
                playerId,
                new MapRunDefinition(
                        new MapDifficulty(difficulty),
                        "forest",
                        "spider",
                        "extermination",
                        List.of("swarm"),
                        seed,
                        1,
                        1,
                        "founding"
                ),
                "map.issue"
        );
    }

    private UUID evidenceReservation(UUID runId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT encounter_reservation_id
                     FROM map_open_player_state_evidence
                     WHERE run_id = ?
                     """)) {
            statement.setObject(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Missing Map open evidence for run " + runId);
                return row.getObject("encounter_reservation_id", UUID.class);
            }
        }
    }

    private void assertItemOwned(UUID itemId, UUID playerId, long stateVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version
                     FROM item_instances
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals("PLAYER_INVENTORY", row.getString("location_kind"));
                assertEquals(playerId, row.getObject("location_id", UUID.class));
                assertEquals(stateVersion, row.getLong("state_version"));
            }
        }
    }

    private long count(String table) throws SQLException {
        if (!List.of("map_runs", "map_open_player_state_evidence").contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private ItemCatalog mapCatalog() {
        return new ItemCatalog(List.of(new ItemDefinition(
                MAP_DEFINITION_ID,
                "PAPER",
                "Reserved Test Map",
                1,
                ItemCategory.PROGRESSION,
                ItemIdentityKind.INDIVIDUAL
        )));
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

    private record PlayerContext(UUID playerId, SessionLease session) { }
}
