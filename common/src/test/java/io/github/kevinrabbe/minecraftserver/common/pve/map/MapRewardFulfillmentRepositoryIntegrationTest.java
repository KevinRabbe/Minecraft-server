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
import java.nio.charset.StandardCharsets;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapRewardFulfillmentRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-13T18:00:00Z");
    private static final String SOURCE_MAP = "map.basic";
    private static final String SUCCESSOR_MAP = "map.successor";
    private static final String COMMODITY = "map.dust";
    private static final String UNIQUE_ITEM = "map.relic";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ItemCatalog itemCatalog;
    private MapAuthorityRepository mapAuthority;
    private CommodityDeliveryAuthority commodityDeliveries;
    private PendingUniqueDeliveryRepository uniqueDeliveries;
    private MapPendingDeliveryAuthority mapDeliveries;

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
        itemCatalog = new ItemCatalog(List.of(
                individual(SOURCE_MAP, "PAPER"),
                individual(SUCCESSOR_MAP, "MAP"),
                commodity(COMMODITY, "GLOWSTONE_DUST"),
                individual(UNIQUE_ITEM, "AMETHYST_SHARD")
        ));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        mapAuthority = new MapAuthorityRepository(dataSource, itemCatalog, clock);
        CommodityDefinitionResolver commodityResolver = definitionId -> {
            ItemDefinition definition = itemCatalog.require(definitionId);
            if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                throw new MapAuthorityException("not a commodity: " + definitionId);
            }
            return definition.definitionId();
        };
        commodityDeliveries = new CommodityDeliveryAuthority(dataSource, commodityResolver, clock);
        uniqueDeliveries = new PendingUniqueDeliveryRepository(dataSource, itemCatalog);
        mapDeliveries = new MapPendingDeliveryAuthority(dataSource, itemCatalog, clock);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
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
        if (database != null) {
            database.close();
        }
    }

    @Test
    void allRewardKindsFulfillExactlyOnceIntoDurableDeliveryCustody() throws Exception {
        UUID playerId = player("FulfillmentOwner");
        UUID runId = completedRun(playerId);
        MapRewardResolver resolver = new MapRewardResolver() {
            @Override
            public int version() {
                return 5;
            }

            @Override
            public List<MapRewardDefinition> resolve(MapRunSnapshot run, List<UUID> participants) {
                return List.of(
                        MapRewardDefinition.commodity(playerId, COMMODITY, 20),
                        MapRewardDefinition.uniqueItem(playerId, UNIQUE_ITEM),
                        MapRewardDefinition.map(playerId, SUCCESSOR_MAP, successor(run))
                );
            }
        };
        MapRewardSettlementRepository settlements = new MapRewardSettlementRepository(
                dataSource,
                itemCatalog,
                resolver,
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)
        );
        MapRewardSettlementResult settlement = settlements.settle(UUID.randomUUID(), runId, 2);
        MapRewardFulfillmentRepository fulfillment = fulfillmentRepository();

        MapRewardGrantSnapshot commodity = fulfillment.fulfill(settlement.grants().get(0).grantId());
        MapRewardGrantSnapshot unique = fulfillment.fulfill(settlement.grants().get(1).grantId());
        MapRewardGrantSnapshot map = fulfillment.fulfill(settlement.grants().get(2).grantId());

        assertEquals(MapRewardGrantStatus.FULFILLED, commodity.status());
        assertEquals(MapRewardGrantStatus.FULFILLED, unique.status());
        assertEquals(MapRewardGrantStatus.FULFILLED, map.status());
        assertNotNull(commodity.fulfillmentReferenceId());
        assertNotNull(unique.fulfillmentReferenceId());
        assertNotNull(map.fulfillmentReferenceId());

        assertEquals(commodity, fulfillment.fulfill(commodity.grantId()));
        assertEquals(unique, fulfillment.fulfill(unique.grantId()));
        assertEquals(map, fulfillment.fulfill(map.grantId()));

        assertEquals(1L, countWhere("pending_commodity_deliveries", "delivery_id", commodity.fulfillmentReferenceId()));
        assertEquals(1L, countWhere("pending_unique_deliveries", "delivery_id", unique.fulfillmentReferenceId()));
        assertEquals(1L, countWhere("pending_unique_deliveries", "delivery_id", map.fulfillmentReferenceId()));
        assertMapDeliveryProfile(map.fulfillmentReferenceId(), 52);

        MapRewardSettlementResult reloaded = settlements.load(runId);
        assertEquals(
                List.of(
                        commodity.fulfillmentReferenceId(),
                        unique.fulfillmentReferenceId(),
                        map.fulfillmentReferenceId()
                ),
                reloaded.grants().stream().map(MapRewardGrantSnapshot::fulfillmentReferenceId).toList()
        );
    }

    @Test
    void retryAfterDeliveryCommittedButBeforeGrantMarkReusesSameDelivery() throws Exception {
        UUID playerId = player("CrashBoundary");
        UUID runId = completedRun(playerId);
        MapRewardSettlementRepository settlements = new MapRewardSettlementRepository(
                dataSource,
                itemCatalog,
                new MapRewardResolver() {
                    @Override
                    public int version() {
                        return 1;
                    }

                    @Override
                    public List<MapRewardDefinition> resolve(MapRunSnapshot run, List<UUID> participants) {
                        return List.of(MapRewardDefinition.commodity(playerId, COMMODITY, 7));
                    }
                }
        );
        MapRewardGrantSnapshot pending = settlements.settle(UUID.randomUUID(), runId, 2).grants().getFirst();
        UUID deterministicOperation = fulfillmentOperation(pending.grantId());
        UUID precreatedDelivery = commodityDeliveries.createPending(
                deterministicOperation,
                playerId,
                COMMODITY,
                7
        ).deliveryId();
        assertEquals(MapRewardGrantStatus.PENDING, pending.status());

        MapRewardGrantSnapshot fulfilled = fulfillmentRepository().fulfill(pending.grantId());

        assertEquals(precreatedDelivery, fulfilled.fulfillmentReferenceId());
        assertEquals(deterministicOperation, fulfilled.fulfillmentOperationId());
        assertEquals(1L, countWhere("pending_commodity_deliveries", "source_operation_id", deterministicOperation));
    }

    @Test
    void successorMapPendingDeliveryContainsImmutableMapProfile() throws Exception {
        UUID playerId = player("SuccessorProfile");
        MapRunDefinition profile = new MapRunDefinition(
                new MapDifficulty(88),
                "forest",
                "spider",
                "elite_hunt",
                List.of("swarm", "frenzied"),
                999L,
                4,
                8,
                "founding"
        );
        UUID operationId = UUID.randomUUID();

        MapPendingDeliveryResult first = mapDeliveries.createPending(
                operationId,
                SUCCESSOR_MAP,
                playerId,
                profile,
                "map.reward"
        );
        MapPendingDeliveryResult retry = mapDeliveries.createPending(
                operationId,
                SUCCESSOR_MAP,
                playerId,
                profile,
                "map.reward"
        );

        assertEquals(first, retry);
        assertEquals(profile, first.mapProfile().runDefinition());
        assertPendingItem(first.deliveryId(), first.mapProfile().itemInstanceId(), playerId);
    }

    private MapRewardFulfillmentRepository fulfillmentRepository() {
        return new MapRewardFulfillmentRepository(
                dataSource,
                commodityDeliveries,
                uniqueDeliveries,
                mapDeliveries,
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC)
        );
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

    private void assertMapDeliveryProfile(UUID deliveryId, int expectedDifficulty) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT p.difficulty
                     FROM pending_unique_deliveries d
                     JOIN map_item_profiles p ON p.item_instance_id = d.item_instance_id
                     WHERE d.delivery_id = ?
                     """)) {
            statement.setObject(1, deliveryId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(expectedDifficulty, row.getInt("difficulty"));
            }
        }
    }

    private void assertPendingItem(UUID deliveryId, UUID itemId, UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT d.recipient_player_id, i.location_kind, i.location_id
                     FROM pending_unique_deliveries d
                     JOIN item_instances i ON i.item_instance_id = d.item_instance_id
                     WHERE d.delivery_id = ? AND d.item_instance_id = ?
                     """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(playerId, row.getObject("recipient_player_id", UUID.class));
                assertEquals("PENDING_DELIVERY", row.getString("location_kind"));
                assertEquals(deliveryId, row.getObject("location_id", UUID.class));
            }
        }
    }

    private long countWhere(String table, String column, UUID value) throws SQLException {
        if (!List.of("pending_commodity_deliveries", "pending_unique_deliveries").contains(table)
                || !List.of("delivery_id", "source_operation_id").contains(column)) {
            throw new IllegalArgumentException("unsupported count target");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?"
             )) {
            statement.setObject(1, value);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
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

    private static UUID fulfillmentOperation(UUID grantId) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:map-reward:" + grantId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ItemDefinition individual(String id, String material) {
        return new ItemDefinition(id, material, id, 1, ItemCategory.PROGRESSION, ItemIdentityKind.INDIVIDUAL);
    }

    private static ItemDefinition commodity(String id, String material) {
        return new ItemDefinition(id, material, id, 64, ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
