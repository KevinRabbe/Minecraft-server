package io.github.kevinrabbe.minecraftserver.common.item;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ItemLiveContentCompatibilityValidatorIntegrationTest {
    private static final String SWORD = "equipment.compatibility_sword";
    private static final String OTHER = "equipment.other_sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;

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
        identities = new PlayerIdentityRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
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
        if (database != null) database.close();
    }

    @Test
    void liveItemMayBeRetunedButCannotLoseIdentityOrFrozenStatCompatibility() throws Exception {
        ItemCatalog original = catalog(equipment(SWORD, "IRON_SWORD", 10_000, 12_000, "damage"));
        ItemCatalog retuned = catalog(equipment(SWORD, "DIAMOND_SWORD", 9_000, 13_000, "damage"));
        ItemCatalog missing = catalog(equipment(OTHER, "STONE_SWORD", 10_000, 11_000, "damage"));
        ItemCatalog commodity = catalog(new ItemDefinition(
                SWORD,
                "IRON_INGOT",
                "Compatibility Commodity",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        ));
        ItemCatalog individualNonEquipment = catalog(new ItemDefinition(
                SWORD,
                "PAPER",
                "Compatibility Relic",
                1,
                ItemCategory.HISTORICAL,
                ItemIdentityKind.INDIVIDUAL
        ));
        ItemCatalog incompatibleRollShape = catalog(equipment(
                SWORD,
                "IRON_SWORD",
                10_000,
                12_000,
                "speed"
        ));

        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "ItemCompat");
        UUID itemId = insertItem(playerId, "{\"damage\":5000}", 2);

        assertDoesNotThrow(() -> ItemLiveContentCompatibilityValidator.validate(dataSource, original));
        assertDoesNotThrow(() -> ItemLiveContentCompatibilityValidator.validate(dataSource, retuned));
        assertThrows(
                ItemCatalogException.class,
                () -> ItemLiveContentCompatibilityValidator.validate(dataSource, missing)
        );
        assertThrows(
                ItemCatalogException.class,
                () -> ItemLiveContentCompatibilityValidator.validate(dataSource, commodity)
        );
        assertThrows(
                ItemCatalogException.class,
                () -> ItemLiveContentCompatibilityValidator.validate(dataSource, individualNonEquipment)
        );
        assertThrows(
                ItemCatalogException.class,
                () -> ItemLiveContentCompatibilityValidator.validate(dataSource, incompatibleRollShape)
        );

        UniqueItemAuthorityRepository authority = new UniqueItemAuthorityRepository(dataSource, original);
        UniqueItemAuthorityResult destroyed = authority.move(
                UUID.randomUUID(),
                itemId,
                0,
                ItemLocation.playerInventory(playerId),
                ItemLocation.destroyed(),
                "test.item_compatibility_destroy",
                playerId
        );
        assertEquals(ItemLocation.destroyed(), destroyed.location());
        assertDoesNotThrow(() -> ItemLiveContentCompatibilityValidator.validate(dataSource, missing));
    }

    private UUID insertItem(UUID playerId, String rollStateJson, int upgradeLevel) throws SQLException {
        UUID itemId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO item_instances(
                            item_instance_id,
                            definition_id,
                            location_kind,
                            location_id,
                            state_version,
                            original_owner_player_id,
                            created_by_operation_id,
                            created_reason,
                            roll_state,
                            upgrade_level
                        ) VALUES (?, ?, 'PLAYER_INVENTORY', ?, 0, ?, ?, 'test.item_compatibility', ?::jsonb, ?)
                        """)) {
                    statement.setObject(1, itemId);
                    statement.setString(2, SWORD);
                    statement.setObject(3, playerId);
                    statement.setObject(4, playerId);
                    statement.setObject(5, operationId);
                    statement.setString(6, rollStateJson);
                    statement.setInt(7, upgradeLevel);
                    assertEquals(1, statement.executeUpdate());
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO item_provenance(
                            item_instance_id,
                            sequence_no,
                            operation_id,
                            event_type,
                            from_location_kind,
                            from_location_id,
                            to_location_kind,
                            to_location_id,
                            reason,
                            actor_player_id
                        ) VALUES (?, 0, ?, 'CREATED', NULL, NULL, 'PLAYER_INVENTORY', ?, 'test.item_compatibility', ?)
                        """)) {
                    statement.setObject(1, itemId);
                    statement.setObject(2, operationId);
                    statement.setObject(3, playerId);
                    statement.setObject(4, playerId);
                    assertEquals(1, statement.executeUpdate());
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        return itemId;
    }

    private static ItemCatalog catalog(ItemDefinition definition) {
        return new ItemCatalog(List.of(definition));
    }

    private static ItemDefinition equipment(
            String definitionId,
            String material,
            int minimumBasisPoints,
            int maximumBasisPoints,
            String statKey
    ) {
        return new ItemDefinition(
                definitionId,
                material,
                "Compatibility Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL,
                new ItemRollProfile(Map.of(statKey, new RollRange(minimumBasisPoints, maximumBasisPoints)))
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
