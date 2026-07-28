package io.github.kevinrabbe.minecraftserver.common.pve.map;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapAuthorityRepositoryIntegrationTest {
    private static final String MAP_DEFINITION_ID = "map.basic";
    private static final Instant NOW = Instant.parse("2026-08-10T18:00:00Z");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private MapAuthorityRepository maps;

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
        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                MAP_DEFINITION_ID,
                "PAPER",
                "Test Map",
                1,
                ItemCategory.PROGRESSION,
                ItemIdentityKind.INDIVIDUAL
        )));
        maps = new MapAuthorityRepository(
                dataSource,
                catalog,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        map_clears,
                        map_run_participants,
                        map_runs,
                        map_item_profiles,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
                        historical_events,
                        world_eras,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
        insertEra("founding", 0, NOW.minusSeconds(3_600));
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void issueOpenStartCompleteIsExactlyOnceAndProducesOneClear() throws Exception {
        UUID playerId = player("MapOwner");
        MapRunDefinition definition = definition(42, 7L);
        UUID issueOperation = UUID.randomUUID();

        MapItemProfile issued = maps.issueMap(
                issueOperation,
                MAP_DEFINITION_ID,
                playerId,
                definition,
                "map.issue"
        );
        MapItemProfile issueRetry = maps.issueMap(
                issueOperation,
                MAP_DEFINITION_ID,
                playerId,
                definition,
                "map.issue"
        );
        assertEquals(issued, issueRetry);
        assertItemLocation(issued.itemInstanceId(), "PLAYER_INVENTORY", playerId, 0L);

        UUID openOperation = UUID.randomUUID();
        UUID runId = maps.openMap(openOperation, issued.itemInstanceId(), playerId, 0, "map.open");
        assertEquals(runId, maps.openMap(openOperation, issued.itemInstanceId(), playerId, 0, "map.open"));
        assertItemLocation(issued.itemInstanceId(), "DESTROYED", null, 1L);
        assertEquals(1L, count("map_runs"));
        assertEquals(1L, countProvenanceEvent(issued.itemInstanceId(), openOperation, "DESTROYED"));

        MapRunSnapshot created = maps.loadRun(runId);
        assertEquals(MapRunStatus.CREATED, created.status());
        assertEquals(definition, created.definition());
        assertEquals(0L, created.stateVersion());

        UUID startOperation = UUID.randomUUID();
        maps.startRun(startOperation, runId, 0, List.of(playerId), "map.start");
        maps.startRun(startOperation, runId, 0, List.of(playerId), "map.start");
        MapRunSnapshot active = maps.loadRun(runId);
        assertEquals(MapRunStatus.ACTIVE, active.status());
        assertEquals(1L, active.stateVersion());
        assertEquals(List.of(playerId), maps.listParticipants(runId));

        UUID completeOperation = UUID.randomUUID();
        MapClearSnapshot clear = maps.completeRun(
                completeOperation,
                runId,
                1,
                12_345,
                "map.complete"
        );
        MapClearSnapshot completeRetry = maps.completeRun(
                completeOperation,
                runId,
                1,
                12_345,
                "map.complete"
        );
        assertEquals(clear, completeRetry);
        assertEquals(runId, clear.runId());
        assertTrue(clear.solo());
        assertEquals(42, clear.difficulty().value());
        assertEquals(12_345L, clear.elapsedMillis());
        assertEquals(1L, count("map_clears"));

        MapRunSnapshot completed = maps.loadRun(runId);
        assertEquals(MapRunStatus.COMPLETED, completed.status());
        assertEquals(2L, completed.stateVersion());
        assertEquals(clear, maps.listHighestClears(true, "founding", 10).getFirst());
    }

    @Test
    void concurrentOpenAttemptsConsumeOneMapIntoOnlyOneRun() throws Exception {
        UUID playerId = player("RaceOwner");
        MapItemProfile issued = maps.issueMap(
                UUID.randomUUID(),
                MAP_DEFINITION_ID,
                playerId,
                definition(60, 9L),
                "map.issue"
        );

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<UUID> first = () -> maps.openMap(
                    UUID.randomUUID(), issued.itemInstanceId(), playerId, 0, "map.open"
            );
            Callable<UUID> second = () -> maps.openMap(
                    UUID.randomUUID(), issued.itemInstanceId(), playerId, 0, "map.open"
            );
            Future<UUID> a = executor.submit(first);
            Future<UUID> b = executor.submit(second);

            int successes = 0;
            UUID successfulRun = null;
            for (Future<UUID> future : List.of(a, b)) {
                try {
                    UUID runId = future.get();
                    successes++;
                    successfulRun = runId;
                } catch (Exception expected) {
                    assertTrue(expected.getCause() instanceof MapAuthorityException
                            || expected.getCause() instanceof SQLException);
                }
            }

            assertEquals(1, successes);
            assertTrue(successfulRun != null);
            assertEquals(1L, count("map_runs"));
            assertItemLocation(issued.itemInstanceId(), "DESTROYED", null, 1L);
        }
    }

    @Test
    void staleOpenDoesNotConsumeMap() throws Exception {
        UUID playerId = player("StaleOwner");
        MapItemProfile issued = maps.issueMap(
                UUID.randomUUID(),
                MAP_DEFINITION_ID,
                playerId,
                definition(12, 3L),
                "map.issue"
        );

        assertThrows(
                MapAuthorityException.class,
                () -> maps.openMap(UUID.randomUUID(), issued.itemInstanceId(), playerId, 1, "map.open")
        );
        assertItemLocation(issued.itemInstanceId(), "PLAYER_INVENTORY", playerId, 0L);
        assertEquals(0L, count("map_runs"));
    }

    @Test
    void participantSetCannotChangeAfterStart() throws Exception {
        UUID owner = player("PartyOwner");
        UUID latePlayer = player("LatePlayer");
        MapItemProfile issued = maps.issueMap(
                UUID.randomUUID(),
                MAP_DEFINITION_ID,
                owner,
                definition(25, 11L),
                "map.issue"
        );
        UUID runId = maps.openMap(UUID.randomUUID(), issued.itemInstanceId(), owner, 0, "map.open");
        maps.startRun(UUID.randomUUID(), runId, 0, List.of(owner), "map.start");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO map_run_participants(run_id, player_id)
                     VALUES (?, ?)
                     """)) {
            statement.setObject(1, runId);
            statement.setObject(2, latePlayer);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
        assertEquals(List.of(owner), maps.listParticipants(runId));
    }

    @Test
    void failedRunNeverRestoresOpenedMapOrCreatesClear() throws Exception {
        UUID playerId = player("FailureOwner");
        MapItemProfile issued = maps.issueMap(
                UUID.randomUUID(),
                MAP_DEFINITION_ID,
                playerId,
                definition(90, 15L),
                "map.issue"
        );
        UUID runId = maps.openMap(UUID.randomUUID(), issued.itemInstanceId(), playerId, 0, "map.open");
        UUID failureOperation = UUID.randomUUID();

        maps.failRun(failureOperation, runId, 0, "map.runtime_failure");
        maps.failRun(failureOperation, runId, 0, "map.runtime_failure");

        assertEquals(MapRunStatus.FAILED, maps.loadRun(runId).status());
        assertItemLocation(issued.itemInstanceId(), "DESTROYED", null, 1L);
        assertEquals(0L, count("map_clears"));
    }

    @Test
    void immutableMapProfileCannotBeRewrittenAfterIssuance() throws Exception {
        UUID playerId = player("ImmutableOwner");
        MapItemProfile issued = maps.issueMap(
                UUID.randomUUID(),
                MAP_DEFINITION_ID,
                playerId,
                definition(33, 99L),
                "map.issue"
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE map_item_profiles
                     SET difficulty = difficulty + 1
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, issued.itemInstanceId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
        assertEquals(33, maps.loadMapProfile(issued.itemInstanceId()).runDefinition().difficulty().value());
    }

    @Test
    void operationIdsCannotBeReboundToDifferentMapRequests() throws Exception {
        UUID playerId = player("RetryOwner");
        UUID issueOperation = UUID.randomUUID();
        MapItemProfile issued = maps.issueMap(
                issueOperation,
                MAP_DEFINITION_ID,
                playerId,
                definition(20, 1L),
                "map.issue"
        );

        assertThrows(
                MapAuthorityException.class,
                () -> maps.issueMap(
                        issueOperation,
                        MAP_DEFINITION_ID,
                        playerId,
                        definition(21, 1L),
                        "map.issue"
                )
        );

        UUID openOperation = UUID.randomUUID();
        UUID runId = maps.openMap(openOperation, issued.itemInstanceId(), playerId, 0, "map.open");
        assertThrows(
                MapAuthorityException.class,
                () -> maps.openMap(openOperation, issued.itemInstanceId(), playerId, 1, "map.open")
        );

        UUID startOperation = UUID.randomUUID();
        maps.startRun(startOperation, runId, 0, List.of(playerId), "map.start");
        assertThrows(
                MapAuthorityException.class,
                () -> maps.startRun(startOperation, runId, 1, List.of(playerId), "map.start")
        );
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private static MapRunDefinition definition(int difficulty, long seed) {
        return new MapRunDefinition(
                new MapDifficulty(difficulty),
                "forest",
                "spider",
                "extermination",
                List.of("swarm"),
                seed,
                2,
                4,
                "founding"
        );
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

    private void assertItemLocation(
            UUID itemInstanceId,
            String expectedKind,
            UUID expectedLocationId,
            long expectedVersion
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version
                     FROM item_instances
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(expectedKind, row.getString("location_kind"));
                assertEquals(expectedLocationId, row.getObject("location_id", UUID.class));
                assertEquals(expectedVersion, row.getLong("state_version"));
            }
        }
    }

    private long countProvenanceEvent(UUID itemId, UUID operationId, String eventType) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM item_provenance
                     WHERE item_instance_id = ? AND operation_id = ? AND event_type = ?
                     """)) {
            statement.setObject(1, itemId);
            statement.setObject(2, operationId);
            statement.setString(3, eventType);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long count(String table) throws SQLException {
        if (!List.of("map_runs", "map_clears").contains(table)) {
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
}
