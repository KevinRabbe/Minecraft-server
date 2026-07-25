package io.github.kevinrabbe.minecraftserver.common.pve.map;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapPlayerStateOpenRepositoryIntegrationTest {
    private static final String MAP_DEFINITION_ID = "map.stateful";
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final byte[] NEXT_PAYLOAD = new byte[]{7, 1, 9};

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private MapAuthorityRepository maps;
    private MapPlayerStateOpenRepository statefulMaps;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                6
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                MAP_DEFINITION_ID,
                "PAPER",
                "Stateful Test Map",
                1,
                ItemCategory.PROGRESSION,
                ItemIdentityKind.INDIVIDUAL
        )));
        maps = new MapAuthorityRepository(dataSource, catalog);
        statefulMaps = new MapPlayerStateOpenRepository(
                dataSource,
                catalog,
                (playerId, itemId, expectedItemVersion, currentPayload, nextPayload) -> {
                    assertEquals(0L, expectedItemVersion);
                    assertArrayEquals(NEXT_PAYLOAD, nextPayload);
                }
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        map_open_player_state_evidence,
                        map_clears,
                        map_run_participants,
                        map_runs,
                        map_item_profiles,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
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
            statement.execute("INSERT INTO backends(backend_id, status) VALUES ('paper-a', 'ONLINE')");
        }
        insertEra("founding", 0, Instant.parse("2026-07-25T12:00:00Z"));
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void openConsumesSerializedRepresentationAndItemIntoOneRunExactlyOnce() throws Exception {
        PlayerContext player = player("MapStateOwner");
        MapItemProfile issued = issue(player.playerId(), 31, 11L);
        UUID operationId = UUID.randomUUID();

        MapPlayerStateOpenResult first = statefulMaps.openMap(
                operationId,
                issued.itemInstanceId(),
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                0,
                "city",
                "portal",
                NEXT_PAYLOAD,
                "map.open"
        );
        MapPlayerStateOpenResult retry = statefulMaps.openMap(
                operationId,
                issued.itemInstanceId(),
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                0,
                "city",
                "portal",
                NEXT_PAYLOAD,
                "map.open"
        );

        assertEquals(first, retry);
        assertEquals(player.playerId(), first.playerId());
        assertEquals(1L, first.playerStateVersion());
        assertEquals(1L, first.destroyedItemStateVersion());
        assertArrayEquals(NEXT_PAYLOAD, states.load(player.playerId()).statePayload());
        assertEquals(1L, states.load(player.playerId()).stateVersion());
        assertItem(issued.itemInstanceId(), "DESTROYED", null, 1L);
        assertEquals(MapRunStatus.CREATED, maps.loadRun(first.runId()).status());
        assertEquals(1L, count("map_runs"));
        assertEquals(1L, count("map_open_player_state_evidence"));
    }

    @Test
    void retryCannotRebindPayloadOrStateRequest() throws Exception {
        PlayerContext player = player("MapStateRetry");
        MapItemProfile issued = issue(player.playerId(), 20, 22L);
        UUID operationId = UUID.randomUUID();

        statefulMaps.openMap(
                operationId,
                issued.itemInstanceId(),
                player.session().sessionId(),
                "paper-a",
                0,
                0,
                "city",
                "portal",
                NEXT_PAYLOAD,
                "map.open"
        );

        assertThrows(
                MapAuthorityException.class,
                () -> statefulMaps.openMap(
                        operationId,
                        issued.itemInstanceId(),
                        player.session().sessionId(),
                        "paper-a",
                        0,
                        0,
                        "city",
                        "portal",
                        new byte[]{1, 2, 3},
                        "map.open"
                )
        );
        assertThrows(
                MapAuthorityException.class,
                () -> statefulMaps.openMap(
                        operationId,
                        issued.itemInstanceId(),
                        player.session().sessionId(),
                        "paper-a",
                        1,
                        0,
                        "city",
                        "portal",
                        NEXT_PAYLOAD,
                        "map.open"
                )
        );
        assertEquals(1L, count("map_runs"));
    }

    @Test
    void rejectedSerializedRemovalRollsBackPlayerStateItemAndRun() throws Exception {
        PlayerContext player = player("MapStateReject");
        MapItemProfile issued = issue(player.playerId(), 18, 33L);
        MapPlayerStateOpenRepository rejecting = new MapPlayerStateOpenRepository(
                dataSource,
                mapCatalog(),
                (playerId, itemId, expectedVersion, currentPayload, nextPayload) -> {
                    throw new MapAuthorityException("serialized Map representation was not removed exactly");
                }
        );

        assertThrows(
                MapAuthorityException.class,
                () -> rejecting.openMap(
                        UUID.randomUUID(),
                        issued.itemInstanceId(),
                        player.session().sessionId(),
                        "paper-a",
                        0,
                        0,
                        "city",
                        "portal",
                        NEXT_PAYLOAD,
                        "map.open"
                )
        );

        assertEquals(0L, states.load(player.playerId()).stateVersion());
        assertItem(issued.itemInstanceId(), "PLAYER_INVENTORY", player.playerId(), 0L);
        assertEquals(0L, count("map_runs"));
        assertEquals(0L, count("map_open_player_state_evidence"));
    }

    @Test
    void concurrentDifferentOperationsCanCreateOnlyOneRun() throws Exception {
        PlayerContext player = player("MapStateRace");
        MapItemProfile issued = issue(player.playerId(), 55, 44L);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MapPlayerStateOpenResult> a = executor.submit(() -> statefulMaps.openMap(
                    UUID.randomUUID(), issued.itemInstanceId(), player.session().sessionId(), "paper-a",
                    0, 0, "city", "portal", NEXT_PAYLOAD, "map.open"
            ));
            Future<MapPlayerStateOpenResult> b = executor.submit(() -> statefulMaps.openMap(
                    UUID.randomUUID(), issued.itemInstanceId(), player.session().sessionId(), "paper-a",
                    0, 0, "city", "portal", NEXT_PAYLOAD, "map.open"
            ));

            int successes = 0;
            for (Future<MapPlayerStateOpenResult> future : List.of(a, b)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof MapAuthorityException
                            || expected.getCause() instanceof SQLException
                            || expected.getCause() instanceof RuntimeException);
                }
            }
            assertEquals(1, successes);
        }

        assertEquals(1L, count("map_runs"));
        assertEquals(1L, count("map_open_player_state_evidence"));
        assertItem(issued.itemInstanceId(), "DESTROYED", null, 1L);
        assertEquals(1L, states.load(player.playerId()).stateVersion());
    }

    @Test
    void stateEvidenceIsAppendOnly() throws Exception {
        PlayerContext player = player("MapStateAudit");
        MapItemProfile issued = issue(player.playerId(), 14, 55L);
        MapPlayerStateOpenResult opened = statefulMaps.openMap(
                UUID.randomUUID(), issued.itemInstanceId(), player.session().sessionId(), "paper-a",
                0, 0, "city", "portal", NEXT_PAYLOAD, "map.open"
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM map_open_player_state_evidence
                     WHERE run_id = ?
                     """)) {
            statement.setObject(1, opened.runId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private PlayerContext player(String name) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        return new PlayerContext(playerId, session);
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

    private ItemCatalog mapCatalog() {
        return new ItemCatalog(List.of(new ItemDefinition(
                MAP_DEFINITION_ID,
                "PAPER",
                "Stateful Test Map",
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

    private void assertItem(UUID itemId, String kind, UUID locationId, long stateVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version
                     FROM item_instances
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(kind, row.getString("location_kind"));
                assertEquals(locationId, row.getObject("location_id", UUID.class));
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

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerContext(UUID playerId, SessionLease session) { }
}
