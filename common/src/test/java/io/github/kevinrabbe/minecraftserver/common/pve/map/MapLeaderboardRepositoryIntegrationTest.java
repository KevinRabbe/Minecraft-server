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
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapLeaderboardRepositoryIntegrationTest {
    private static final String MAP_ID = "map.leaderboard";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private MapAuthorityRepository maps;
    private MapLeaderboardRepository leaderboards;

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
                MAP_ID,
                "MAP",
                "Leaderboard Map",
                1,
                ItemCategory.PROGRESSION,
                ItemIdentityKind.INDIVIDUAL
        )));
        maps = new MapAuthorityRepository(dataSource, catalog);
        leaderboards = new MapLeaderboardRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
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
                        player_names,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
        insertEra();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void highestAndFastestBoardsUseAuthoritativeClearOrderingAndCurrentNames() throws Exception {
        PlayerRef alice = player("Alice");
        PlayerRef bob = player("Bob");
        PlayerRef carol = player("Carol");
        PlayerRef dave = player("Dave");

        complete(50, 9_000, List.of(alice.playerId()), 101L);
        complete(50, 8_000, List.of(bob.playerId()), 102L);
        complete(60, 15_000, List.of(carol.playerId()), 103L);
        complete(55, 7_000, List.of(alice.playerId(), bob.playerId()), 104L);
        complete(55, 6_000, List.of(carol.playerId(), dave.playerId()), 105L);

        assertEquals(bob.playerId(), identities.ensurePlayer(bob.minecraftUuid(), "Bobby"));

        List<MapLeaderboardEntry> solo = leaderboards.highest(true, 10);
        assertEquals(3, solo.size());
        assertEquals(60, solo.get(0).difficulty().value());
        assertEquals("Carol", solo.get(0).participants().getFirst().playerName());
        assertEquals(50, solo.get(1).difficulty().value());
        assertEquals(8_000L, solo.get(1).elapsedMillis());
        assertEquals("Bobby", solo.get(1).participants().getFirst().playerName());
        assertEquals(9_000L, solo.get(2).elapsedMillis());
        assertEquals(List.of(1, 2, 3), solo.stream().map(MapLeaderboardEntry::rank).toList());

        List<MapLeaderboardEntry> fastest50 = leaderboards.fastest(true, new MapDifficulty(50), 10);
        assertEquals(2, fastest50.size());
        assertEquals("Bobby", fastest50.get(0).participants().getFirst().playerName());
        assertEquals(8_000L, fastest50.get(0).elapsedMillis());
        assertEquals("Alice", fastest50.get(1).participants().getFirst().playerName());

        List<MapLeaderboardEntry> group = leaderboards.highest(false, 10);
        assertEquals(2, group.size());
        assertEquals(6_000L, group.get(0).elapsedMillis());
        assertEquals(
                Set.of("Carol", "Dave"),
                group.get(0).participants().stream().map(MapLeaderboardParticipant::playerName).collect(java.util.stream.Collectors.toSet())
        );
        assertEquals(7_000L, group.get(1).elapsedMillis());
        assertEquals(
                Set.of("Alice", "Bobby"),
                group.get(1).participants().stream().map(MapLeaderboardParticipant::playerName).collect(java.util.stream.Collectors.toSet())
        );

        assertThrows(IllegalArgumentException.class, () -> leaderboards.highest(true, 0));
        assertThrows(IllegalArgumentException.class, () -> leaderboards.fastest(true, new MapDifficulty(1), 101));
    }

    private MapClearSnapshot complete(int difficulty, long elapsedMillis, List<UUID> participants, long seed)
            throws SQLException {
        UUID owner = participants.getFirst();
        MapItemProfile issued = maps.issueMap(
                UUID.randomUUID(),
                MAP_ID,
                owner,
                definition(difficulty, seed),
                "map.issue"
        );
        UUID runId = maps.openMap(UUID.randomUUID(), issued.itemInstanceId(), owner, 0, "map.open");
        MapRunSnapshot created = maps.loadRun(runId);
        maps.startRun(UUID.randomUUID(), runId, created.stateVersion(), participants, "map.start");
        MapRunSnapshot active = maps.loadRun(runId);
        return maps.completeRun(
                UUID.randomUUID(),
                runId,
                active.stateVersion(),
                elapsedMillis,
                "map.complete"
        );
    }

    private MapRunDefinition definition(int difficulty, long seed) {
        return new MapRunDefinition(
                new MapDifficulty(difficulty),
                "forest",
                "spider",
                "extermination",
                List.of("swarm"),
                seed,
                1,
                1,
                "founding"
        );
    }

    private PlayerRef player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        return new PlayerRef(minecraftUuid, identities.ensurePlayer(minecraftUuid, name));
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

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerRef(UUID minecraftUuid, UUID playerId) { }
}
