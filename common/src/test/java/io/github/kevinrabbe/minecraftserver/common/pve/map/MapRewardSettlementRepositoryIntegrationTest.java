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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapRewardSettlementRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");
    private static final String SOURCE_MAP = "map.basic";
    private static final String SUCCESSOR_MAP = "map.successor";
    private static final String COMMODITY = "map.dust";
    private static final String UNIQUE_ITEM = "map.relic";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ItemCatalog itemCatalog;
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
        itemCatalog = new ItemCatalog(List.of(
                individual(SOURCE_MAP, "PAPER"),
                individual(SUCCESSOR_MAP, "MAP"),
                commodity(COMMODITY, "GLOWSTONE_DUST"),
                individual(UNIQUE_ITEM, "AMETHYST_SHARD")
        ));
        maps = new MapAuthorityRepository(dataSource, itemCatalog, Clock.fixed(NOW, ZoneOffset.UTC));
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
    void settlementPersistsResolvedRewardsOnceAndRetryDoesNotReroll() throws Exception {
        UUID playerId = player("RewardOwner");
        UUID runId = completedRun(playerId);
        AtomicInteger resolveCalls = new AtomicInteger();
        MapRewardResolver resolver = new MapRewardResolver() {
            @Override
            public int version() {
                return 3;
            }

            @Override
            public List<MapRewardDefinition> resolve(MapRunSnapshot run, List<UUID> participants) {
                resolveCalls.incrementAndGet();
                UUID recipient = participants.getFirst();
                return List.of(
                        MapRewardDefinition.commodity(recipient, COMMODITY, 25),
                        MapRewardDefinition.uniqueItem(recipient, UNIQUE_ITEM),
                        MapRewardDefinition.map(recipient, SUCCESSOR_MAP, successorDefinition(run))
                );
            }
        };
        MapRewardSettlementRepository rewards = new MapRewardSettlementRepository(
                dataSource,
                itemCatalog,
                resolver,
                Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC)
        );

        UUID operationId = UUID.randomUUID();
        MapRewardSettlementResult first = rewards.settle(operationId, runId, 2);
        MapRewardSettlementResult retry = rewards.settle(operationId, runId, 2);

        assertEquals(first, retry);
        assertEquals(1, resolveCalls.get());
        assertEquals(3, first.resolverVersion());
        assertEquals(3, first.grants().size());
        assertEquals(List.of(MapRewardKind.COMMODITY, MapRewardKind.UNIQUE_ITEM, MapRewardKind.MAP),
                first.grants().stream().map(MapRewardGrantSnapshot::kind).toList());
        assertEquals(1L, count("map_reward_settlements"));
        assertEquals(3L, count("map_reward_grants"));
        assertEquals(operationId, rewardOperationForRun(runId));
    }

    @Test
    void differentOperationCannotSettleSameCompletedRunTwice() throws Exception {
        UUID playerId = player("SingleSettlement");
        UUID runId = completedRun(playerId);
        MapRewardResolver resolver = oneCommodityResolver(playerId);
        MapRewardSettlementRepository rewards = new MapRewardSettlementRepository(dataSource, itemCatalog, resolver);

        rewards.settle(UUID.randomUUID(), runId, 2);

        assertThrows(
                MapAuthorityException.class,
                () -> rewards.settle(UUID.randomUUID(), runId, 2)
        );
        assertEquals(1L, count("map_reward_settlements"));
    }

    @Test
    void resolverCannotRewardNonParticipant() throws Exception {
        UUID playerId = player("Participant");
        UUID outsider = player("Outsider");
        UUID runId = completedRun(playerId);
        MapRewardResolver resolver = new MapRewardResolver() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public List<MapRewardDefinition> resolve(MapRunSnapshot run, List<UUID> participants) {
                return List.of(MapRewardDefinition.commodity(outsider, COMMODITY, 1));
            }
        };
        MapRewardSettlementRepository rewards = new MapRewardSettlementRepository(dataSource, itemCatalog, resolver);

        assertThrows(MapAuthorityException.class, () -> rewards.settle(UUID.randomUUID(), runId, 2));
        assertEquals(0L, count("map_reward_settlements"));
        assertEquals(null, rewardOperationForRun(runId));
    }

    @Test
    void rewardKindMustMatchItemIdentity() throws Exception {
        UUID playerId = player("IdentityOwner");
        UUID runId = completedRun(playerId);
        MapRewardResolver resolver = new MapRewardResolver() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public List<MapRewardDefinition> resolve(MapRunSnapshot run, List<UUID> participants) {
                return List.of(MapRewardDefinition.commodity(playerId, UNIQUE_ITEM, 2));
            }
        };
        MapRewardSettlementRepository rewards = new MapRewardSettlementRepository(dataSource, itemCatalog, resolver);

        assertThrows(MapAuthorityException.class, () -> rewards.settle(UUID.randomUUID(), runId, 2));
        assertEquals(0L, count("map_reward_settlements"));
    }

    @Test
    void persistedGrantDefinitionCannotBeRewritten() throws Exception {
        UUID playerId = player("ImmutableGrant");
        UUID runId = completedRun(playerId);
        MapRewardSettlementRepository rewards = new MapRewardSettlementRepository(
                dataSource,
                itemCatalog,
                oneCommodityResolver(playerId)
        );
        MapRewardGrantSnapshot grant = rewards.settle(UUID.randomUUID(), runId, 2).grants().getFirst();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE map_reward_grants
                     SET quantity = quantity + 1
                     WHERE grant_id = ?
                     """)) {
            statement.setObject(1, grant.grantId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void pendingRewardsAreQueryablePerPlayer() throws Exception {
        UUID playerId = player("PendingReward");
        UUID runId = completedRun(playerId);
        MapRewardSettlementRepository rewards = new MapRewardSettlementRepository(
                dataSource,
                itemCatalog,
                oneCommodityResolver(playerId)
        );
        rewards.settle(UUID.randomUUID(), runId, 2);

        List<MapRewardGrantSnapshot> pending = rewards.listPending(playerId, 10);
        assertEquals(1, pending.size());
        assertEquals(MapRewardGrantStatus.PENDING, pending.getFirst().status());
        assertEquals(COMMODITY, pending.getFirst().definitionId());
    }

    private MapRewardResolver oneCommodityResolver(UUID playerId) {
        return new MapRewardResolver() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public List<MapRewardDefinition> resolve(MapRunSnapshot run, List<UUID> participants) {
                return List.of(MapRewardDefinition.commodity(playerId, COMMODITY, 5));
            }
        };
    }

    private UUID completedRun(UUID playerId) throws Exception {
        MapRunDefinition sourceDefinition = new MapRunDefinition(
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
        MapItemProfile map = maps.issueMap(
                UUID.randomUUID(),
                SOURCE_MAP,
                playerId,
                sourceDefinition,
                "map.issue"
        );
        UUID runId = maps.openMap(UUID.randomUUID(), map.itemInstanceId(), playerId, 0, "map.open");
        maps.startRun(UUID.randomUUID(), runId, 0, List.of(playerId), "map.start");
        maps.completeRun(UUID.randomUUID(), runId, 1, 10_000, "map.complete");
        assertEquals(2L, maps.loadRun(runId).stateVersion());
        return runId;
    }

    private static MapRunDefinition successorDefinition(MapRunSnapshot source) {
        return new MapRunDefinition(
                new MapDifficulty(source.definition().difficulty().value() + 2),
                source.definition().environmentId(),
                source.definition().enemyFamilyId(),
                source.definition().objectiveId(),
                source.definition().modifierIds(),
                source.definition().generationSeed() + 1,
                source.definition().generationVersion(),
                source.definition().balanceVersion(),
                source.definition().worldEraId()
        );
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

    private UUID rewardOperationForRun(UUID runId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT reward_operation_id
                     FROM map_runs
                     WHERE run_id = ?
                     """)) {
            statement.setObject(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getObject("reward_operation_id", UUID.class);
            }
        }
    }

    private long count(String table) throws SQLException {
        if (!List.of("map_reward_settlements", "map_reward_grants").contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
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
