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
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class IndividualItemLiveContentCompatibilityValidatorIntegrationTest {
    private static final String DEFINITION = "verify.live_sword";
    private static final String OTHER_DEFINITION = "verify.other_item";

    private Database database;
    private DataSource dataSource;
    private UUID playerId;
    private UUID itemInstanceId;

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
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        item_upgrade_events,
                        item_provenance,
                        asset_ledger,
                        processed_operations,
                        item_instances,
                        player_names,
                        player_state,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }

        playerId = new PlayerIdentityRepository(dataSource).ensurePlayer(UUID.randomUUID(), "LiveItemVerifier");
        ItemCatalog original = catalog(equipment(
                DEFINITION,
                "IRON_SWORD",
                "Verifier Sword",
                Map.of("damage", new RollRange(10_000, 12_000))
        ));
        itemInstanceId = new UniqueItemAuthorityRepository(dataSource, original)
                .createForPlayer(UUID.randomUUID(), DEFINITION, playerId, "verify.item.create", playerId)
                .itemInstanceId();
        setFrozenState("{\"damage\":5000}", 3);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void stableIdentityAcceptsMaterialDisplayAndRangeRetuning() {
        ItemCatalog retuned = catalog(equipment(
                DEFINITION,
                "DIAMOND_SWORD",
                "Retuned Verifier Sword",
                Map.of("damage", new RollRange(9_000, 15_000))
        ));

        assertDoesNotThrow(() -> IndividualItemLiveContentCompatibilityValidator.validate(dataSource, retuned));
    }

    @Test
    void missingOrCommodityDefinitionIsRejected() {
        assertThrows(
                ItemCatalogException.class,
                () -> IndividualItemLiveContentCompatibilityValidator.validate(
                        dataSource,
                        catalog(equipment(
                                OTHER_DEFINITION,
                                "IRON_SWORD",
                                "Other Sword",
                                Map.of("damage", new RollRange(10_000, 12_000))
                        ))
                )
        );

        ItemDefinition commodity = new ItemDefinition(
                DEFINITION,
                "IRON_INGOT",
                "Invalid Commodity Replacement",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        );
        assertThrows(
                ItemCatalogException.class,
                () -> IndividualItemLiveContentCompatibilityValidator.validate(dataSource, catalog(commodity))
        );
    }

    @Test
    void incompatibleFrozenRollShapeIsRejected() {
        ItemCatalog changedKeys = catalog(equipment(
                DEFINITION,
                "IRON_SWORD",
                "Verifier Sword",
                Map.of("speed", new RollRange(10_000, 12_000))
        ));

        assertThrows(
                ItemCatalogException.class,
                () -> IndividualItemLiveContentCompatibilityValidator.validate(dataSource, changedKeys)
        );
    }

    @Test
    void upgradedItemMustRemainEquipment() {
        ItemDefinition usable = new ItemDefinition(
                DEFINITION,
                "STICK",
                "Invalid Usable Replacement",
                1,
                ItemCategory.USABLES,
                ItemIdentityKind.INDIVIDUAL
        );

        assertThrows(
                ItemCatalogException.class,
                () -> IndividualItemLiveContentCompatibilityValidator.validate(dataSource, catalog(usable))
        );
    }

    @Test
    void destroyedHistoryDoesNotPinObsoleteDefinition() throws Exception {
        new UniqueItemAuthorityRepository(dataSource, catalog(equipment(
                DEFINITION,
                "IRON_SWORD",
                "Verifier Sword",
                Map.of("damage", new RollRange(10_000, 12_000))
        ))).move(
                UUID.randomUUID(),
                itemInstanceId,
                0,
                ItemLocation.playerInventory(playerId),
                ItemLocation.destroyed(),
                "verify.item.destroy",
                playerId
        );

        assertDoesNotThrow(() -> IndividualItemLiveContentCompatibilityValidator.validate(
                dataSource,
                catalog(equipment(
                        OTHER_DEFINITION,
                        "IRON_SWORD",
                        "Other Sword",
                        Map.of("damage", new RollRange(10_000, 12_000))
                ))
        ));
    }

    private void setFrozenState(String rollStateJson, int upgradeLevel) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE item_instances
                        SET roll_state = ?::jsonb,
                            upgrade_level = ?
                        WHERE item_instance_id = ?
                        """)) {
                    statement.setString(1, rollStateJson);
                    statement.setInt(2, upgradeLevel);
                    statement.setObject(3, itemInstanceId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static ItemDefinition equipment(
            String definitionId,
            String material,
            String displayName,
            Map<String, RollRange> rollProperties
    ) {
        return new ItemDefinition(
                definitionId,
                material,
                displayName,
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL,
                new ItemRollProfile(rollProperties)
        );
    }

    private static ItemCatalog catalog(ItemDefinition... definitions) {
        return new ItemCatalog(List.of(definitions));
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
