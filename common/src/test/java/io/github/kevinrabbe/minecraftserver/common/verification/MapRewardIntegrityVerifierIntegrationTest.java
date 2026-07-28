package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDefinitionResolver;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliveryAuthority;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryRepository;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapItemProfile;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapPendingDeliveryAuthority;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardGrantSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardKind;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardResolver;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardSettlementRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardSettlementResult;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunSnapshot;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapRewardIntegrityVerifierIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-14T18:00:00Z");
    private static final String ERA = "founding";
    private static final String SOURCE_MAP = "verify.map.source";
    private static final String SUCCESSOR_MAP = "verify.map.successor";
    private static final String COMMODITY = "verify.map.dust";
    private static final String UNIQUE_ITEM = "verify.map.relic";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ItemCatalog itemCatalog;
    private MapAuthorityRepository mapAuthority;
    private CommodityDeliveryAuthority commodityDeliveries;
    private PendingUniqueDeliveryRepository uniqueDeliveries;
    private MapPendingDeliveryAuthority mapDeliveries;
    private MapRewardIntegrityVerifier verifier;

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
        itemCatalog = new ItemCatalog(List.of(
                individual(SOURCE_MAP, "MAP"),
                individual(SUCCESSOR_MAP, "MAP"),
                commodity(COMMODITY, "GLOWSTONE_DUST"),
                individual(UNIQUE_ITEM, "AMETHYST_SHARD")
        ));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        mapAuthority = new MapAuthorityRepository(dataSource, itemCatalog, clock);
        CommodityDefinitionResolver commodityResolver = definitionId -> {
            ItemDefinition definition = itemCatalog.require(definitionId);
            if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                throw new IllegalArgumentException("not a commodity: " + definitionId);
            }
            return definition.definitionId();
        };
        commodityDeliveries = new CommodityDeliveryAuthority(dataSource, commodityResolver, clock);
        uniqueDeliveries = new PendingUniqueDeliveryRepository(dataSource, itemCatalog);
        mapDeliveries = new MapPendingDeliveryAuthority(dataSource, itemCatalog, clock);
        verifier = new MapRewardIntegrityVerifier(dataSource);
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
    void healthySettlementAndAllFulfillmentKindsProduceNoIssues() throws Exception {
        UUID player = player("RewardHealthy");
        MapRewardSettlementResult settlement = settleAllKinds(player);
        MapRewardFulfillmentRepository fulfillment = fulfillmentRepository();
        for (MapRewardGrantSnapshot grant : settlement.grants()) {
            fulfillment.fulfill(grant.grantId());
        }

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void settlementOperationDriftIsReported() throws Exception {
        UUID player = player("RewardSettle");
        MapRewardSettlementResult settlement = settleAllKinds(player);

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE map_runs
                    SET reward_operation_id = ?
                    WHERE run_id = ?
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, settlement.runId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals("MAP_REWARD_SETTLEMENT_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(settlement.runId().toString())));
    }

    @Test
    void rewardGrantAssignedToNonParticipantIsReported() throws Exception {
        UUID player = player("RewardOwner");
        UUID stranger = player("RewardOther");
        MapRewardSettlementResult settlement = settleAllKinds(player);
        UUID grantId = settlement.grants().getFirst().grantId();

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE map_reward_grants
                    SET player_id = ?
                    WHERE grant_id = ?
                    """)) {
                statement.setObject(1, stranger);
                statement.setObject(2, grantId);
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals("MAP_REWARD_GRANT_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(grantId.toString())));
    }

    @Test
    void fulfilledCommodityDeliveryDriftIsReported() throws Exception {
        UUID player = player("RewardCommodity");
        MapRewardSettlementResult settlement = settleAllKinds(player);
        MapRewardGrantSnapshot grant = grant(settlement, MapRewardKind.COMMODITY);
        MapRewardGrantSnapshot fulfilled = fulfillmentRepository().fulfill(grant.grantId());

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE pending_commodity_deliveries
                    SET quantity = quantity + 1
                    WHERE delivery_id = ?
                    """)) {
                statement.setObject(1, fulfilled.fulfillmentReferenceId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals("MAP_REWARD_FULFILLMENT_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(grant.grantId().toString())));
    }

    @Test
    void fulfilledSuccessorMapProfileDriftIsReported() throws Exception {
        UUID player = player("RewardMap");
        MapRewardSettlementResult settlement = settleAllKinds(player);
        MapRewardGrantSnapshot grant = grant(settlement, MapRewardKind.MAP);
        MapRewardGrantSnapshot fulfilled = fulfillmentRepository().fulfill(grant.grantId());

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE map_item_profiles
                    SET difficulty = difficulty + 1
                    WHERE item_instance_id = (
                        SELECT item_instance_id
                        FROM pending_unique_deliveries
                        WHERE delivery_id = ?
                    )
                    """)) {
                statement.setObject(1, fulfilled.fulfillmentReferenceId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals("MAP_REWARD_FULFILLMENT_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(grant.grantId().toString())));
    }

    private MapRewardSettlementResult settleAllKinds(UUID playerId) throws Exception {
        UUID runId = completedRun(playerId);
        MapRewardResolver resolver = new MapRewardResolver() {
            @Override
            public int version() {
                return 7;
            }

            @Override
            public List<MapRewardDefinition> resolve(MapRunSnapshot run, List<UUID> participants) {
                return List.of(
                        MapRewardDefinition.commodity(playerId, COMMODITY, 5),
                        MapRewardDefinition.uniqueItem(playerId, UNIQUE_ITEM),
                        MapRewardDefinition.map(playerId, SUCCESSOR_MAP, successor(run))
                );
            }
        };
        return new MapRewardSettlementRepository(
                dataSource,
                itemCatalog,
                resolver,
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)
        ).settle(UUID.randomUUID(), runId, 2);
    }

    private UUID completedRun(UUID playerId) throws Exception {
        MapItemProfile item = mapAuthority.issueMap(
                UUID.randomUUID(), SOURCE_MAP, playerId, sourceDefinition(), "map.issue"
        );
        UUID runId = mapAuthority.openMap(
                UUID.randomUUID(), item.itemInstanceId(), playerId, 0, "map.open"
        );
        mapAuthority.startRun(UUID.randomUUID(), runId, 0, List.of(playerId), "map.start");
        mapAuthority.completeRun(UUID.randomUUID(), runId, 1, 10_000, "map.complete");
        return runId;
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

    private static MapRewardGrantSnapshot grant(MapRewardSettlementResult settlement, MapRewardKind kind) {
        return settlement.grants().stream()
                .filter(grant -> grant.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static MapRunDefinition sourceDefinition() {
        return new MapRunDefinition(
                new MapDifficulty(20),
                "forest",
                "spider",
                "extermination",
                List.of("swarm"),
                123L,
                2,
                4,
                ERA
        );
    }

    private static MapRunDefinition successor(MapRunSnapshot run) {
        return new MapRunDefinition(
                new MapDifficulty(run.definition().difficulty().value() + 1),
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

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void insertEra() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO world_eras(era_id, sequence_no, started_at)
                     VALUES (?, 0, ?)
                     """)) {
            statement.setString(1, ERA);
            statement.setTimestamp(2, Timestamp.from(NOW.minusSeconds(3_600)));
            statement.executeUpdate();
        }
    }

    private void withReplicationTriggersDisabled(SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
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

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }
}
