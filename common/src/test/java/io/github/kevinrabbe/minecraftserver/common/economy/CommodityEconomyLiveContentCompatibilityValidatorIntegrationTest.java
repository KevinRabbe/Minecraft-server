package io.github.kevinrabbe.minecraftserver.common.economy;

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
class CommodityEconomyLiveContentCompatibilityValidatorIntegrationTest {
    private static final String COMMODITY = "material.compatibility_ingot";
    private static final String OTHER = "material.other_ingot";

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
        originalCatalog = catalog(commodity(COMMODITY, "IRON_INGOT", "Compatibility Ingot", 64));
        retunedCatalog = catalog(commodity(COMMODITY, "GOLD_INGOT", "Retuned Ingot", 16));
        missingCatalog = catalog(commodity(OTHER, "COPPER_INGOT", "Other Ingot", 64));
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
    void pendingDeliveryPinsCommodityUntilClaimed() throws Exception {
        UUID playerId = player("PendingDelivery");
        UUID deliveryId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO pending_commodity_deliveries(
                         delivery_id,
                         player_id,
                         commodity_definition_id,
                         quantity,
                         source_operation_id,
                         status
                     ) VALUES (?, ?, ?, 5, ?, 'PENDING')
                     """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, playerId);
            statement.setString(3, COMMODITY);
            statement.setObject(4, UUID.randomUUID());
            statement.executeUpdate();
        }

        assertLiveDependency();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE pending_commodity_deliveries
                     SET status = 'CLAIMED', claim_operation_id = ?, claimed_at = NOW()
                     WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, deliveryId);
            statement.executeUpdate();
        }
        assertDoesNotThrow(() -> validate(missingCatalog));
    }

    @Test
    void openBazaarOrderPinsCommodityUntilClosed() throws Exception {
        UUID playerId = player("BazaarOrder");
        UUID orderId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO bazaar_orders(
                         order_id,
                         player_id,
                         commodity_definition_id,
                         side,
                         limit_price_minor,
                         original_quantity,
                         remaining_quantity,
                         reserved_money_minor,
                         status,
                         create_operation_id
                     ) VALUES (?, ?, ?, 'SELL', 100, 8, 8, 0, 'OPEN', ?)
                     """)) {
            statement.setObject(1, orderId);
            statement.setObject(2, playerId);
            statement.setString(3, COMMODITY);
            statement.setObject(4, UUID.randomUUID());
            statement.executeUpdate();
        }

        assertLiveDependency();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE bazaar_orders
                     SET status = 'CANCELLED',
                         remaining_quantity = 0,
                         cancel_operation_id = ?,
                         closed_at = NOW()
                     WHERE order_id = ?
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, orderId);
            statement.executeUpdate();
        }
        assertDoesNotThrow(() -> validate(missingCatalog));
    }

    @Test
    void activeSecureTradeEscrowPinsCommodityUntilTerminal() throws Exception {
        UUID playerA = player("TradeA");
        UUID playerB = player("TradeB");
        UUID tradeId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO secure_trades(
                            trade_id,
                            player_a_id,
                            player_b_id,
                            status,
                            revision,
                            create_operation_id
                        ) VALUES (?, ?, ?, 'OPEN', 0, ?)
                        """)) {
                    statement.setObject(1, tradeId);
                    statement.setObject(2, playerA);
                    statement.setObject(3, playerB);
                    statement.setObject(4, UUID.randomUUID());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO secure_trade_commodity_escrow(
                            trade_id,
                            owner_player_id,
                            commodity_definition_id,
                            quantity
                        ) VALUES (?, ?, ?, 3)
                        """)) {
                    statement.setObject(1, tradeId);
                    statement.setObject(2, playerA);
                    statement.setString(3, COMMODITY);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }

        assertLiveDependency();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE secure_trades
                     SET status = 'CANCELLED',
                         cancel_operation_id = ?,
                         settled_at = NOW(),
                         updated_at = NOW()
                     WHERE trade_id = ?
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tradeId);
            statement.executeUpdate();
        }
        assertDoesNotThrow(() -> validate(missingCatalog));
    }

    @Test
    void positiveClanBalancePinsCommodityUntilEmpty() throws Exception {
        UUID playerId = player("ClanLeader");
        UUID clanId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO clans(clan_id, name, tag, created_by_player_id)
                        VALUES (?, 'Compatibility Clan', 'COMP', ?)
                        """)) {
                    statement.setObject(1, clanId);
                    statement.setObject(2, playerId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO clan_members(clan_id, player_id, role)
                        VALUES (?, ?, 'LEADER')
                        """)) {
                    statement.setObject(1, clanId);
                    statement.setObject(2, playerId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO clan_commodity_balances(
                            clan_id,
                            commodity_definition_id,
                            quantity,
                            state_version
                        ) VALUES (?, ?, 12, 0)
                        """)) {
                    statement.setObject(1, clanId);
                    statement.setString(2, COMMODITY);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }

        assertLiveDependency();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_commodity_balances
                     SET quantity = 0, state_version = state_version + 1, updated_at = NOW()
                     WHERE clan_id = ? AND commodity_definition_id = ?
                     """)) {
            statement.setObject(1, clanId);
            statement.setString(2, COMMODITY);
            statement.executeUpdate();
        }
        assertDoesNotThrow(() -> validate(missingCatalog));
    }

    private void assertLiveDependency() {
        assertDoesNotThrow(() -> validate(originalCatalog));
        assertDoesNotThrow(() -> validate(retunedCatalog));
        assertThrows(ItemCatalogException.class, () -> validate(missingCatalog));
        assertThrows(ItemCatalogException.class, () -> validate(nonCommodityCatalog));
    }

    private void validate(ItemCatalog itemCatalog) throws SQLException {
        CommodityEconomyLiveContentCompatibilityValidator.validate(dataSource, itemCatalog);
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
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
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        );
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
