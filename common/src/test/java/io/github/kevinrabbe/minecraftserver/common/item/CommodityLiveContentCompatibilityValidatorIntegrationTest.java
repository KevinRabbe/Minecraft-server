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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CommodityLiveContentCompatibilityValidatorIntegrationTest {
    private static final String COMMODITY = "material.wheat";
    private static final String REPLACEMENT = "material.raw_iron";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;

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
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE players RESTART IDENTITY CASCADE");
            statement.execute("TRUNCATE TABLE backends RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void liveSqlAuthoritiesRequireStableCommodityIdentityButAllowRepresentationRetuning() throws Exception {
        LiveAuthorities live = insertEveryLiveAuthority();

        assertDoesNotThrow(() -> validate(catalog(commodity(COMMODITY, "WHEAT", 64))));
        assertDoesNotThrow(() -> validate(catalog(commodity(COMMODITY, "HAY_BLOCK", 16))));
        assertThrows(
                ItemCatalogException.class,
                () -> validate(catalog(commodity(REPLACEMENT, "RAW_IRON", 64)))
        );
        assertThrows(
                ItemCatalogException.class,
                () -> validate(catalog(individual(COMMODITY)))
        );

        releaseEveryAuthority(live);
        assertDoesNotThrow(() -> validate(catalog(commodity(REPLACEMENT, "RAW_IRON", 64))));
    }

    private LiveAuthorities insertEveryLiveAuthority() throws SQLException {
        UUID playerA = identities.ensurePlayer(UUID.randomUUID(), "CommodityA");
        UUID playerB = identities.ensurePlayer(UUID.randomUUID(), "CommodityB");
        UUID deliveryId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();
        UUID commissionId = UUID.randomUUID();
        UUID clanId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID harvestId = UUID.randomUUID();
        UUID backendInstanceId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        INSERT INTO pending_commodity_deliveries(
                            delivery_id, player_id, commodity_definition_id, quantity, source_operation_id
                        ) VALUES (?, ?, ?, 5, ?)
                        """, deliveryId, playerA, COMMODITY, UUID.randomUUID());

                execute(connection, """
                        INSERT INTO bazaar_orders(
                            order_id, player_id, commodity_definition_id, side,
                            limit_price_minor, original_quantity, remaining_quantity,
                            reserved_money_minor, status, create_operation_id
                        ) VALUES (?, ?, ?, 'SELL', 100, 5, 5, 0, 'OPEN', ?)
                        """, orderId, playerA, COMMODITY, UUID.randomUUID());

                execute(connection, """
                        INSERT INTO secure_trades(
                            trade_id, player_a_id, player_b_id, status, create_operation_id
                        ) VALUES (?, ?, ?, 'OPEN', ?)
                        """, tradeId, playerA, playerB, UUID.randomUUID());
                execute(connection, """
                        INSERT INTO secure_trade_commodity_escrow(
                            trade_id, owner_player_id, commodity_definition_id, quantity
                        ) VALUES (?, ?, ?, 3)
                        """, tradeId, playerA, COMMODITY);

                execute(connection, """
                        INSERT INTO crafting_commissions(
                            commission_id, requester_player_id, recipe_id, recipe_version,
                            status, payment_minor, create_operation_id
                        ) VALUES (?, ?, 'recipe.compat', 1, 'OPEN', 0, ?)
                        """, commissionId, playerA, UUID.randomUUID());
                execute(connection, """
                        INSERT INTO crafting_commission_materials(
                            commission_id, commodity_definition_id, quantity
                        ) VALUES (?, ?, 4)
                        """, commissionId, COMMODITY);

                execute(connection, """
                        INSERT INTO clans(clan_id, name, tag, created_by_player_id)
                        VALUES (?, 'Commodity Clan', 'CMOD', ?)
                        """, clanId, playerA);
                execute(connection, """
                        INSERT INTO clan_commodity_balances(
                            clan_id, commodity_definition_id, quantity, state_version
                        ) VALUES (?, ?, 7, 1)
                        """, clanId, COMMODITY);

                execute(connection, """
                        INSERT INTO bounty_pouches(player_id, family_id, pouch_tier, state_version)
                        VALUES (?, 'zombie', 1, 1)
                        """, playerA);
                execute(connection, """
                        INSERT INTO bounty_pouch_balances(
                            player_id, family_id, commodity_definition_id, quantity, state_version
                        ) VALUES (?, 'zombie', ?, 9, 1)
                        """, playerA, COMMODITY);

                execute(connection, """
                        INSERT INTO backends(backend_id, status)
                        VALUES ('paper-commodity-compat', 'ONLINE')
                        """);
                execute(connection, """
                        INSERT INTO zone_instances(
                            instance_id, zone_id, template_version, backend_id, status,
                            player_count, soft_capacity, hard_capacity
                        ) VALUES (?, 'farm', 'farm-v1', 'paper-commodity-compat', 'ACTIVE', 0, 20, 30)
                        """, backendInstanceId);
                execute(connection, """
                        INSERT INTO resource_sources(
                            source_id, instance_id, source_key, definition_id
                        ) VALUES (?, ?, 'wheat.01', 'resource.wheat')
                        """, sourceId, backendInstanceId);
                execute(connection, """
                        INSERT INTO resource_harvests(
                            harvest_id, operation_id, source_id, source_cycle_no, player_id,
                            commodity_definition_id, commodity_quantity
                        ) VALUES (?, ?, ?, 1, ?, ?, 2)
                        """, harvestId, UUID.randomUUID(), sourceId, playerA, COMMODITY);

                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }

        return new LiveAuthorities(deliveryId, orderId, tradeId, commissionId, clanId, playerA, harvestId);
    }

    private void releaseEveryAuthority(LiveAuthorities live) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        UPDATE pending_commodity_deliveries
                        SET status = 'CLAIMED', claim_operation_id = ?, claimed_at = NOW()
                        WHERE delivery_id = ?
                        """, UUID.randomUUID(), live.deliveryId());
                execute(connection, """
                        UPDATE bazaar_orders
                        SET status = 'CANCELLED', remaining_quantity = 0, reserved_money_minor = 0,
                            cancel_operation_id = ?, closed_at = NOW()
                        WHERE order_id = ?
                        """, UUID.randomUUID(), live.orderId());
                execute(connection, """
                        UPDATE secure_trades
                        SET status = 'CANCELLED', cancel_operation_id = ?, settled_at = NOW()
                        WHERE trade_id = ?
                        """, UUID.randomUUID(), live.tradeId());
                execute(connection, """
                        UPDATE crafting_commissions
                        SET status = 'CANCELLED', cancel_operation_id = ?, settled_at = NOW()
                        WHERE commission_id = ?
                        """, UUID.randomUUID(), live.commissionId());
                execute(connection, """
                        UPDATE clan_commodity_balances
                        SET quantity = 0, state_version = state_version + 1
                        WHERE clan_id = ? AND commodity_definition_id = ?
                        """, live.clanId(), COMMODITY);
                execute(connection, """
                        UPDATE bounty_pouch_balances
                        SET quantity = 0, state_version = state_version + 1
                        WHERE player_id = ? AND family_id = 'zombie' AND commodity_definition_id = ?
                        """, live.playerId(), COMMODITY);
                execute(connection, """
                        INSERT INTO resource_harvest_fulfillments(harvest_id, commodity_delivery_id)
                        VALUES (?, ?)
                        """, live.harvestId(), live.deliveryId());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void validate(ItemCatalog catalog) throws SQLException {
        CommodityLiveContentCompatibilityValidator.validate(dataSource, catalog);
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private static ItemCatalog catalog(ItemDefinition definition) {
        return new ItemCatalog(List.of(definition));
    }

    private static ItemDefinition commodity(String definitionId, String material, int maxStack) {
        return new ItemDefinition(
                definitionId,
                material,
                definitionId,
                maxStack,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        );
    }

    private static ItemDefinition individual(String definitionId) {
        return new ItemDefinition(
                definitionId,
                "WOODEN_SWORD",
                definitionId,
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        );
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }

    private record LiveAuthorities(
            UUID deliveryId,
            UUID orderId,
            UUID tradeId,
            UUID commissionId,
            UUID clanId,
            UUID playerId,
            UUID harvestId
    ) { }
}
