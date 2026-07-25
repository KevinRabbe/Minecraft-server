package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BountyPouchRepositoryIntegrationTest {
    private static final BountyFamilyId FAMILY = new BountyFamilyId("zombie");
    private static final String MATERIAL = "bounty.rotten_flesh";
    private static final String UNIQUE = "bounty.sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private BountyPouchRepository pouches;

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
        pouches = new BountyPouchRepository(
                dataSource,
                new ItemCatalog(List.of(
                        new ItemDefinition(
                                MATERIAL, "ROTTEN_FLESH", "Bounty Flesh", 64,
                                ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY
                        ),
                        new ItemDefinition(
                                UNIQUE, "IRON_SWORD", "Bounty Sword", 1,
                                ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL
                        )
                ))
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        pending_commodity_deliveries,
                        bounty_pouch_balances,
                        bounty_pouches,
                        processed_operations,
                        player_names,
                        player_state,
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
    void withdrawalIsExactlyOnceAndCreatesOneDurableDelivery() throws Exception {
        UUID playerId = playerWithPouch("PouchOwner", 10);
        UUID operationId = UUID.randomUUID();

        BountyPouchWithdrawalResult first = pouches.withdraw(
                operationId, playerId, FAMILY, MATERIAL, 4, "bounty.pouch_withdraw"
        );
        BountyPouchWithdrawalResult retry = pouches.withdraw(
                operationId, playerId, FAMILY, MATERIAL, 4, "bounty.pouch_withdraw"
        );

        assertEquals(first, retry);
        assertEquals(6L, first.balance().quantity());
        assertEquals(1L, first.balance().stateVersion());
        assertEquals(4L, first.withdrawnQuantity());
        assertEquals(1L, pendingDeliveryCount());
        assertPendingDelivery(first.deliveryId(), playerId, 4);
        assertEquals(1L, processedCount(operationId));
        assertEquals(6L, pouches.loadBalance(playerId, FAMILY, MATERIAL).orElseThrow().quantity());

        assertThrows(
                BountyException.class,
                () -> pouches.withdraw(operationId, playerId, FAMILY, MATERIAL, 3, "bounty.pouch_withdraw")
        );
    }

    @Test
    void overdrawRollsBackBalanceAndDelivery() throws Exception {
        UUID playerId = playerWithPouch("PouchOverdraw", 5);
        UUID operationId = UUID.randomUUID();

        assertThrows(
                BountyException.class,
                () -> pouches.withdraw(operationId, playerId, FAMILY, MATERIAL, 6, "bounty.pouch_withdraw")
        );

        BountyPouchBalanceSnapshot balance = pouches.loadBalance(playerId, FAMILY, MATERIAL).orElseThrow();
        assertEquals(5L, balance.quantity());
        assertEquals(0L, balance.stateVersion());
        assertEquals(0L, pendingDeliveryCount());
        assertEquals(0L, processedCount(operationId));
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawPouch() throws Exception {
        UUID playerId = playerWithPouch("PouchRace", 10);
        int successes = 0;

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<BountyPouchWithdrawalResult> first = executor.submit(
                    () -> pouches.withdraw(
                            UUID.randomUUID(), playerId, FAMILY, MATERIAL, 7, "bounty.pouch_withdraw"
                    )
            );
            Future<BountyPouchWithdrawalResult> second = executor.submit(
                    () -> pouches.withdraw(
                            UUID.randomUUID(), playerId, FAMILY, MATERIAL, 7, "bounty.pouch_withdraw"
                    )
            );
            for (Future<BountyPouchWithdrawalResult> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof BountyException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(3L, pouches.loadBalance(playerId, FAMILY, MATERIAL).orElseThrow().quantity());
        assertEquals(1L, pendingDeliveryCount());
    }

    @Test
    void nonCommodityDefinitionCannotBeWithdrawn() throws Exception {
        UUID playerId = playerWithPouch("PouchType", 10);
        assertThrows(
                BountyException.class,
                () -> pouches.withdraw(
                        UUID.randomUUID(), playerId, FAMILY, UNIQUE, 1, "bounty.pouch_withdraw"
                )
        );
        assertEquals(10L, pouches.loadBalance(playerId, FAMILY, MATERIAL).orElseThrow().quantity());
    }

    private UUID playerWithPouch(String name, long quantity) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement pouch = connection.prepareStatement("""
                    INSERT INTO bounty_pouches(player_id, family_id)
                    VALUES (?, ?)
                    """);
                 PreparedStatement balance = connection.prepareStatement("""
                    INSERT INTO bounty_pouch_balances(
                        player_id, family_id, commodity_definition_id, quantity, state_version
                    ) VALUES (?, ?, ?, ?, 0)
                    """)) {
                pouch.setObject(1, playerId);
                pouch.setString(2, FAMILY.value());
                pouch.executeUpdate();
                balance.setObject(1, playerId);
                balance.setString(2, FAMILY.value());
                balance.setString(3, MATERIAL);
                balance.setLong(4, quantity);
                balance.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return playerId;
    }

    private long pendingDeliveryCount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM pending_commodity_deliveries")) {
            row.next();
            return row.getLong(1);
        }
    }

    private long processedCount(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM processed_operations WHERE operation_id = ?
                     """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private void assertPendingDelivery(UUID deliveryId, UUID playerId, long quantity) throws SQLException {
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
                assertEquals(MATERIAL, row.getString("commodity_definition_id"));
                assertEquals(quantity, row.getLong("quantity"));
                assertEquals("PENDING", row.getString("status"));
            }
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
