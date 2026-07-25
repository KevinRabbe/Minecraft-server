package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRollProfile;
import io.github.kevinrabbe.minecraftserver.common.item.RollRange;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CraftingRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String IRON = "craft.iron";
    private static final String STEEL = "craft.steel";
    private static final String SWORD = "craft.sword";
    private static final SkillId SMITHING = new SkillId("smithing");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private ItemCatalog items;
    private SkillProgressionCatalog skills;
    private CraftRecipeCatalog recipes;

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
        items = new ItemCatalog(List.of(
                commodity(IRON, "IRON_INGOT"),
                commodity(STEEL, "IRON_NUGGET"),
                new ItemDefinition(
                        SWORD,
                        "IRON_SWORD",
                        "Rolled Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                )
        ));
        skills = new SkillProgressionCatalog(List.of(linearSkill(SMITHING)));
        recipes = new CraftRecipeCatalog(List.of(
                new CraftRecipeVersion(
                        1,
                        new CraftRecipeDefinition(
                                "steel.refine",
                                List.of(new RecipeIngredient(IRON, 2)),
                                STEEL,
                                1,
                                null,
                                0
                        ),
                        ItemRollProfile.NONE
                ),
                new CraftRecipeVersion(
                        3,
                        new CraftRecipeDefinition(
                                "sword.forge",
                                List.of(new RecipeIngredient(IRON, 5)),
                                SWORD,
                                1,
                                SMITHING,
                                10
                        ),
                        new ItemRollProfile(Map.of(
                                "damage", new RollRange(10_000, 12_000),
                                "speed", new RollRange(9_500, 10_500)
                        ))
                )
        ), items);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        craft_records,
                        pending_unique_deliveries,
                        pending_commodity_deliveries,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
                        player_skills,
                        player_sessions,
                        player_state,
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void commodityCraftConsumesExactIngredientsAndCreatesOnePendingDeliveryExactlyOnce() throws Exception {
        PlayerContext player = playerWithSession("CommodityCrafter", new byte[]{8});
        AtomicInteger validations = new AtomicInteger();
        CraftingRepository crafting = repository((playerId, materials, current, next) -> {
            validations.incrementAndGet();
            assertEquals(player.playerId(), playerId);
            assertEquals(Map.of(IRON, 2L), materials);
            assertArrayEquals(new byte[]{8}, current);
            assertArrayEquals(new byte[]{6}, next);
        });
        UUID operationId = UUID.randomUUID();

        CraftExecutionResult first = crafting.craftFromPlayerState(
                operationId,
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                "steel.refine",
                1,
                "city",
                "forge",
                new byte[]{6},
                "craft.execute"
        );
        CraftExecutionResult retry = crafting.craftFromPlayerState(
                operationId,
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                "steel.refine",
                1,
                "city",
                "forge",
                new byte[]{6},
                "craft.execute"
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertEquals(STEEL, first.outputDefinitionId());
        assertEquals(1L, first.outputQuantity());
        assertNull(first.itemInstanceId());
        assertTrue(first.rollQualityBasisPoints().isEmpty());
        assertCommodityDelivery(first.deliveryId(), player.playerId(), STEEL, 1);
        assertEquals(1L, rowCount("craft_records"));
        assertEquals(1L, processedCount(operationId));
        assertArrayEquals(new byte[]{6}, states.load(player.playerId()).statePayload());
    }

    @Test
    void rolledIndividualCraftPersistsNormalizedRollsAndCannotBeRerolled() throws Exception {
        PlayerContext player = playerWithSession("RolledCrafter", new byte[]{9});
        setSkillExperience(player.playerId(), SMITHING, 1_000);
        CraftingRepository crafting = repository((playerId, materials, current, next) -> { });

        CraftExecutionResult result = crafting.craftFromPlayerState(
                UUID.randomUUID(),
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                "sword.forge",
                3,
                "city",
                "forge",
                new byte[]{4},
                "craft.execute"
        );

        assertNotNull(result.itemInstanceId());
        assertEquals(Map.of("damage", result.rollQualityBasisPoints().get("damage"),
                "speed", result.rollQualityBasisPoints().get("speed")), result.rollQualityBasisPoints());
        result.rollQualityBasisPoints().values().forEach(value -> assertTrue(value >= 0 && value <= 10_000));
        assertUniqueDelivery(result.deliveryId(), player.playerId(), result.itemInstanceId());
        assertPersistedRollState(result.itemInstanceId(), result.rollQualityBasisPoints());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE item_instances
                     SET roll_state = '{"damage":0,"speed":0}'::jsonb
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, result.itemInstanceId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
        assertPersistedRollState(result.itemInstanceId(), result.rollQualityBasisPoints());
    }

    @Test
    void skillRequirementIsCheckedBeforeInventoryMutation() throws Exception {
        PlayerContext player = playerWithSession("LowSkillCrafter", new byte[]{9});
        AtomicInteger validations = new AtomicInteger();
        CraftingRepository crafting = repository((playerId, materials, current, next) -> validations.incrementAndGet());

        assertThrows(
                CraftingException.class,
                () -> crafting.craftFromPlayerState(
                        UUID.randomUUID(),
                        player.session().sessionId(),
                        "paper-a",
                        player.session().stateVersion(),
                        "sword.forge",
                        3,
                        "city",
                        "forge",
                        new byte[]{4},
                        "craft.execute"
                )
        );

        assertEquals(0, validations.get());
        assertArrayEquals(new byte[]{9}, states.load(player.playerId()).statePayload());
        assertEquals(0L, rowCount("craft_records"));
        assertEquals(0L, rowCount("item_instances"));
    }

    @Test
    void validatorFailureRollsBackCraftOutputLedgerAndPlayerState() throws Exception {
        PlayerContext player = playerWithSession("RollbackCrafter", new byte[]{8});
        CraftingRepository crafting = repository((playerId, materials, current, next) -> {
            throw new CraftingException("invalid material removal");
        });

        assertThrows(
                CraftingException.class,
                () -> crafting.craftFromPlayerState(
                        UUID.randomUUID(),
                        player.session().sessionId(),
                        "paper-a",
                        player.session().stateVersion(),
                        "steel.refine",
                        1,
                        "city",
                        "forge",
                        new byte[]{6},
                        "craft.execute"
                )
        );

        assertArrayEquals(new byte[]{8}, states.load(player.playerId()).statePayload());
        assertEquals(0L, rowCount("craft_records"));
        assertEquals(0L, rowCount("pending_commodity_deliveries"));
        assertEquals(0L, rowCount("economic_ledger"));
    }

    @Test
    void operationIdCannotBeReboundToAnotherRecipeOrPayload() throws Exception {
        PlayerContext player = playerWithSession("BoundCrafter", new byte[]{8});
        CraftingRepository crafting = repository((playerId, materials, current, next) -> { });
        UUID operationId = UUID.randomUUID();
        crafting.craftFromPlayerState(
                operationId,
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                "steel.refine",
                1,
                "city",
                "forge",
                new byte[]{6},
                "craft.execute"
        );

        assertThrows(
                CraftingException.class,
                () -> crafting.craftFromPlayerState(
                        operationId,
                        player.session().sessionId(),
                        "paper-a",
                        player.session().stateVersion(),
                        "steel.refine",
                        1,
                        "city",
                        "forge",
                        new byte[]{5},
                        "craft.execute"
                )
        );
        assertThrows(
                CraftingException.class,
                () -> crafting.craftFromPlayerState(
                        operationId,
                        player.session().sessionId(),
                        "paper-a",
                        player.session().stateVersion(),
                        "sword.forge",
                        3,
                        "city",
                        "forge",
                        new byte[]{6},
                        "craft.execute"
                )
        );
    }

    @Test
    void malformedRollStateIsRejectedAtDatabaseBoundary() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "MalformedRoll");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO item_instances(
                         item_instance_id, definition_id, location_kind, location_id, state_version,
                         original_owner_player_id, created_by_operation_id, created_reason, roll_state, upgrade_level
                     ) VALUES (?, ?, 'PLAYER_INVENTORY', ?, 0, ?, ?, 'test.roll', ?::jsonb, 0)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, SWORD);
            statement.setObject(3, playerId);
            statement.setObject(4, playerId);
            statement.setObject(5, UUID.randomUUID());
            statement.setString(6, "{\"damage\":10001}");
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private CraftingRepository repository(io.github.kevinrabbe.minecraftserver.common.economy.CommodityBatchEscrowValidator validator) {
        return new CraftingRepository(dataSource, items, recipes, skills, validator);
    }

    private PlayerContext playerWithSession(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long version = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "forge", payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(version, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void setSkillExperience(UUID playerId, SkillId skillId, long experience) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO player_skills(player_id, skill_id, experience, state_version)
                     VALUES (?, ?, ?, 0)
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, skillId.value());
            statement.setLong(3, experience);
            statement.executeUpdate();
        }
    }

    private void assertCommodityDelivery(UUID deliveryId, UUID playerId, String definitionId, long quantity)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id, commodity_definition_id, quantity, status
                     FROM pending_commodity_deliveries
                     WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, deliveryId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(playerId, row.getObject("player_id", UUID.class));
                assertEquals(definitionId, row.getString("commodity_definition_id"));
                assertEquals(quantity, row.getLong("quantity"));
                assertEquals("PENDING", row.getString("status"));
            }
        }
    }

    private void assertUniqueDelivery(UUID deliveryId, UUID playerId, UUID itemId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT recipient_player_id, item_instance_id, status
                     FROM pending_unique_deliveries
                     WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, deliveryId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(playerId, row.getObject("recipient_player_id", UUID.class));
                assertEquals(itemId, row.getObject("item_instance_id", UUID.class));
                assertEquals("PENDING", row.getString("status"));
            }
        }
    }

    private void assertPersistedRollState(UUID itemId, Map<String, Integer> expected) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT roll_state::text AS roll_json
                     FROM item_instances
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                String json = row.getString("roll_json");
                expected.forEach((property, value) -> assertTrue(json.contains("\"" + property + "\": " + value)));
            }
        }
    }

    private long processedCount(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM processed_operations WHERE operation_id = ?"
             )) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long rowCount(String table) throws SQLException {
        if (!List.of(
                "craft_records", "item_instances", "pending_commodity_deliveries", "economic_ledger"
        ).contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static ItemDefinition commodity(String id, String material) {
        return new ItemDefinition(id, material, id, 64, ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY);
    }

    private static SkillProgressionDefinition linearSkill(SkillId skillId) {
        ArrayList<Long> thresholds = new ArrayList<>();
        for (int level = 0; level <= 100; level++) {
            thresholds.add(level * 100L);
        }
        return new SkillProgressionDefinition(skillId, thresholds);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerContext(UUID playerId, SessionLease session) {
    }
}
