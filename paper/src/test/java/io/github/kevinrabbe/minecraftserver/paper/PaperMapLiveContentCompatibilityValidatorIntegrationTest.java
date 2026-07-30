package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapItemProfile;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardResolver;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardSettlementRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PaperMapLiveContentCompatibilityValidatorIntegrationTest {
    private static final String MAP_DEFINITION = "map.forest_extermination";
    private static final String REWARD_DEFINITION = "material.zombie_essence";
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    private Database database;
    private DataSource dataSource;
    private ItemCatalog itemCatalog;
    private PlayerIdentityRepository identities;
    private MapAuthorityRepository maps;
    private PaperMapEncounterContentCatalog launchContent;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                4
        ));
        database.migrate();
        dataSource = database.dataSource();
        itemCatalog = new ItemCatalogLoader().loadResource("/content/items.json");
        identities = new PlayerIdentityRepository(dataSource);
        maps = new MapAuthorityRepository(dataSource, itemCatalog);
        launchContent = PaperMapEncounterContentCatalog.loadResource("/content/map-encounters.json", itemCatalog);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        map_reward_grants,
                        map_reward_settlements,
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
                        player_state,
                        wallets,
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
    void historicalMapRequiresExactContentUntilItsPersistentPromiseIsFullyFrozen() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "MapCompat");
        MapItemProfile unopened = issue(playerId, unsupportedV2(101L));

        assertThrows(
                MapAuthorityException.class,
                () -> PaperMapLiveContentCompatibilityValidator.validate(dataSource, maps, launchContent)
        );

        UUID runId = maps.openMap(
                UUID.randomUUID(),
                unopened.itemInstanceId(),
                playerId,
                0L,
                "map.open"
        );
        assertThrows(
                MapAuthorityException.class,
                () -> PaperMapLiveContentCompatibilityValidator.validate(dataSource, maps, launchContent)
        );

        maps.startRun(UUID.randomUUID(), runId, 0L, List.of(playerId), "map.start");
        assertThrows(
                MapAuthorityException.class,
                () -> PaperMapLiveContentCompatibilityValidator.validate(dataSource, maps, launchContent)
        );

        maps.completeRun(UUID.randomUUID(), runId, 1L, 5_000L, "map.complete");
        assertThrows(
                MapAuthorityException.class,
                () -> PaperMapLiveContentCompatibilityValidator.validate(dataSource, maps, launchContent)
        );

        MapRewardResolver frozenRewardPolicy = new MapRewardResolver() {
            @Override
            public int version() {
                return 99;
            }

            @Override
            public List<MapRewardDefinition> resolve(
                    io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunSnapshot completedRun,
                    List<UUID> participantPlayerIds
            ) {
                return participantPlayerIds.stream()
                        .map(player -> MapRewardDefinition.commodity(player, REWARD_DEFINITION, 1L))
                        .toList();
            }
        };
        new MapRewardSettlementRepository(dataSource, itemCatalog, frozenRewardPolicy).settle(
                UUID.randomUUID(),
                runId,
                2L
        );
        assertDoesNotThrow(
                () -> PaperMapLiveContentCompatibilityValidator.validate(dataSource, maps, launchContent)
        );

        MapItemProfile failedSource = issue(playerId, unsupportedV2(202L));
        UUID failedRun = maps.openMap(
                UUID.randomUUID(),
                failedSource.itemInstanceId(),
                playerId,
                0L,
                "map.open"
        );
        maps.failRun(UUID.randomUUID(), failedRun, 0L, "map.fail");
        assertDoesNotThrow(
                () -> PaperMapLiveContentCompatibilityValidator.validate(dataSource, maps, launchContent)
        );
    }

    private MapItemProfile issue(UUID playerId, MapRunDefinition definition) throws SQLException {
        return maps.issueMap(
                UUID.randomUUID(),
                MAP_DEFINITION,
                playerId,
                definition,
                "map.issue"
        );
    }

    private static MapRunDefinition unsupportedV2(long seed) {
        return new MapRunDefinition(
                new MapDifficulty(1),
                "forest",
                "spider",
                "extermination",
                List.of(),
                seed,
                1,
                2,
                "founding"
        );
    }

    private void insertEra() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO world_eras(era_id, sequence_no, started_at)
                     VALUES ('founding', 0, ?)
                     """)) {
            statement.setTimestamp(1, Timestamp.from(NOW));
            statement.executeUpdate();
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
