package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogException;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BountyPouchLiveContentCompatibilityValidatorIntegrationTest {
    private static final String FAMILY = "zombie";
    private static final String COMMODITY = "material.compatibility_fang";
    private static final String OTHER = "material.other_fang";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ItemCatalog originalCatalog;
    private ItemCatalog retunedCatalog;
    private ItemCatalog missingCatalog;
    private ItemCatalog nonCommodityCatalog;

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
        originalCatalog = catalog(commodity(COMMODITY, "ROTTEN_FLESH", "Compatibility Fang", 64));
        retunedCatalog = catalog(commodity(COMMODITY, "BONE", "Retuned Fang", 1));
        missingCatalog = catalog(commodity(OTHER, "SPIDER_EYE", "Other Fang", 64));
        nonCommodityCatalog = catalog(new ItemDefinition(
                COMMODITY,
                "IRON_SWORD",
                "Wrong Identity",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        ));
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE players RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void positiveBalancePinsCommodityUntilEmpty() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "BountyPouch");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO bounty_pouches(player_id, family_id, pouch_tier, state_version)
                        VALUES (?, ?, 1, 0)
                        """)) {
                    statement.setObject(1, playerId);
                    statement.setString(2, FAMILY);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO bounty_pouch_balances(
                            player_id,
                            family_id,
                            commodity_definition_id,
                            quantity,
                            state_version
                        ) VALUES (?, ?, ?, 20, 0)
                        """)) {
                    statement.setObject(1, playerId);
                    statement.setString(2, FAMILY);
                    statement.setString(3, COMMODITY);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }

        assertDoesNotThrow(() -> validate(originalCatalog));
        assertDoesNotThrow(() -> validate(retunedCatalog));
        assertThrows(ItemCatalogException.class, () -> validate(missingCatalog));
        assertThrows(ItemCatalogException.class, () -> validate(nonCommodityCatalog));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE bounty_pouch_balances
                     SET quantity = 0, state_version = state_version + 1, updated_at = NOW()
                     WHERE player_id = ? AND family_id = ? AND commodity_definition_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, FAMILY);
            statement.setString(3, COMMODITY);
            statement.executeUpdate();
        }

        assertDoesNotThrow(() -> validate(missingCatalog));
    }

    private void validate(ItemCatalog itemCatalog) throws SQLException {
        BountyPouchLiveContentCompatibilityValidator.validate(dataSource, itemCatalog);
    }

    private static ItemCatalog catalog(ItemDefinition definition) {
        return new ItemCatalog(List.of(definition));
    }

    private static ItemDefinition commodity(String id, String material, String displayName, int maxStack) {
        return new ItemDefinition(
                id,
                material,
                displayName,
                maxStack,
                ItemCategory.BOUNTY_MATERIALS,
                ItemIdentityKind.COMMODITY
        );
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
