package io.github.kevinrabbe.minecraftserver.common.pve.map;

import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDefinitionResolver;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliveryAuthority;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapRewardLiveContentCompatibilityValidatorIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-13T18:00:00Z");
    private static final String SOURCE_MAP = "map.compatibility_source";
    private static final String SUCCESSOR_MAP = "map.compatibility_successor";
    private static final String COMMODITY = "map.compatibility_dust";
    private static final String UNIQUE_ITEM = "map.compatibility_relic";
    private static final String OTHER = "map.compatibility_other";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ItemCatalog originalCatalog;
    private ItemCatalog retunedCatalog;
    private ItemCatalog missingCatalog;
    private ItemCatalog wrongCommodityCatalog;
    private ItemCatalog wrongUniqueCatalog;
    private ItemCatalog wrongMapCatalog;
    private MapAuthorityRepository mapAuthority;
    private MapRewardFulfillmentRepository fulfillment;

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

        originalCatalog = catalog(
                individual(SOURCE_MAP, "PAPER", "Source Map"),
                individual(SUCCESSOR_MAP, "MAP", "Successor Map"),
                commodity(COMMODITY, "GLOWSTONE_DUST", "Map Dust", 64),
                individual(UNIQUE_ITEM, "AMETHYST_SHARD", "Map Relic")
        );
        retunedCatalog = catalog(
                individual(SOURCE_MAP, "PAPER", "Retuned Source Map"),
                individual(SUCCESSOR_MAP, "FILLED_MAP", "Retuned Successor Map"),
                commodity(COMMODITY, "REDSTONE", "Retuned Map Dust", 2),
                individual(UNIQUE_ITEM, "ECHO_SHARD", "Retuned Map Relic")
        );
        missingCatalog = catalog(individual(OTHER, "STICK", "Other Item"));
        wrongCommodityCatalog = catalog(
                individual(COMMODITY, "AMETHYST_SHARD", "Wrong Commodity Identity"),
                individual(UNIQUE_ITEM, "ECHO_SHARD", "Map Relic"),
                individual(SUCCESSOR_MAP, "MAP", "Successor Map")
        );
        wrongUniqueCatalog = catalog(
                commodity(COMMODITY, "GLOWSTONE_DUST", "Map Dust", 64),
                commodity(UNIQUE_ITEM, "AMETHYST_SHARD", "Wrong Unique Identity", 64),
                individual(SUCCESSOR_MAP, "MAP", "Successor Map")
        );
        wrongMapCatalog = catalog(
                commodity(COMMODITY, "GLOWSTONE_DUST", "Map Dust", 64),
                individual(UNIQUE_ITEM, "AMETHYST_SHARD", "Map Relic"),
                commodity(SUCCESSOR_MAP, "PAPER", "Wrong Map Identity", 64)
        );

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        mapAuthority = new MapAuthorityRepository(dataSource, originalCatalog, clock);
        CommodityDefinitionResolver commodityResolver = definitionId -> {
            ItemDefinition definition = originalCatalog.require(definitionId);
            if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                throw new MapAuthorityException("not a commodity: " + definitionId);
            }
            return definition.definitionId();
        };
        fulfillment = new MapRewardFulfillmentRepository(
                dataSource,
                new CommodityDeliveryAuthority(dataSource, commodityResolver, clock),
                new PendingUniqueDeliveryRepository(dataSource, originalCatalog),
                new MapPendingDeliveryAuthority(dataSource, originalCatalog, clock),
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC)
        );
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
                        pending_unique_deliveries,
                        pending_commodity_deliveries,
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
        insertEra("founding", 0, NOW.minusSeconds(3600));
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void pendingGrantsPinIdentityUntilRealFulfillmentTransfersCustody() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "MapRewardGate");
        UUID runId = completedRun(playerId);
        MapRewardSettlementRepository settlements = new MapRewardSettlementRepository(
                dataSource,
                originalCatalog,
                new MapRewardResolver() {
                    @Override
                    public int version() {
                        return 1;
                    }

                    @Override
                    public List<MapRewardDefinition> resolve(MapRunSnapshot run, List<UUID> participants) {
                        return List.of(
                                MapRewardDefinition.commodity(playerId, COMMODITY, 20),
                                MapRewardDefinition.uniqueItem(playerId, UNIQUE_ITEM),
                                MapRewardDefinition.map(playerId, SUCCESSOR_MAP, successor(run))
                        );
                    }
                },
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)
        );
        List<MapRewardGrantSnapshot> grants = settlements.settle(UUID.randomUUID(), runId, 2).grants();

        assertDoesNotThrow(() -> validate(originalCatalog));
        assertDoesNotThrow(() -> validate(retunedCatalog));
        assertThrows(MapAuthorityException.class, () -> validate(missingCatalog));
        assertThrows(MapAuthorityException.class, () -> validate(wrongCommodityCatalog));
        assertThrows(MapAuthorityException.class, () -> validate(wrongUniqueCatalog));
        assertThrows(MapAuthorityException.class, () -> validate(wrongMapCatalog));

        for (MapRewardGrantSnapshot grant : grants) {
            fulfillment.fulfill(grant.grantId());
        }

        assertDoesNotThrow(() -> validate(missingCatalog));
    }

    private UUID completedRun(UUID playerId) throws Exception {
        MapRunDefinition source = new MapRunDefinition(
                new MapDifficulty(50),
                "forest",
                "spider",
                "extermination",
                List.of("swarm"),
                123L,
                2,
                4,
                "founding"
        );
        MapItemProfile item = mapAuthority.issueMap(
                UUID.randomUUID(), SOURCE_MAP, playerId, source, "map.issue"
        );
        UUID runId = mapAuthority.openMap(UUID.randomUUID(), item.itemInstanceId(), playerId, 0, "map.open");
        mapAuthority.startRun(UUID.randomUUID(), runId, 0, List.of(playerId), "map.start");
        mapAuthority.completeRun(UUID.randomUUID(), runId, 1, 10_000, "map.complete");
        return runId;
    }

    private static MapRunDefinition successor(MapRunSnapshot run) {
        return new MapRunDefinition(
                new MapDifficulty(run.definition().difficulty().value() + 2),
                run.definition().environmentId(),
                run.definition().enemyFamilyId(),
                run.definition().objectiveId(),
                run.definition().modifierIds(),
                run.definition().generationSeed() + 1,
                run.definition().generationVersion(),
                run.definition().balanceVersion(),
                run.definition().worldEraId()
        );
    }

    private void validate(ItemCatalog itemCatalog) throws SQLException {
        MapRewardLiveContentCompatibilityValidator.validate(dataSource, itemCatalog);
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

    private static ItemCatalog catalog(ItemDefinition... definitions) {
        return new ItemCatalog(List.of(definitions));
    }

    private static ItemDefinition individual(String id, String material, String displayName) {
        return new ItemDefinition(
                id,
                material,
                displayName,
                1,
                ItemCategory.PROGRESSION,
                ItemIdentityKind.INDIVIDUAL
        );
    }

    private static ItemDefinition commodity(String id, String material, String displayName, int maxStack) {
        return new ItemDefinition(
                id,
                material,
                displayName,
                maxStack,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        );
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
