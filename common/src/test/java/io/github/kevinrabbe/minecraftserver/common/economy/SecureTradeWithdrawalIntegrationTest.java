package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityResult;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SecureTradeWithdrawalIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String COMMODITY = "trade.iron";
    private static final String UNIQUE = "trade.sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private SecureTradeRepository trades;
    private SecureTradeAssetRepository assets;
    private SecureTradeWithdrawalRepository withdrawals;
    private UniqueItemAuthorityRepository items;

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
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);
        trades = new SecureTradeRepository(dataSource);
        withdrawals = new SecureTradeWithdrawalRepository(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        COMMODITY, "IRON_INGOT", "Trade Iron", 64,
                        ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY
                ),
                new ItemDefinition(
                        UNIQUE, "IRON_SWORD", "Trade Sword", 1,
                        ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL
                )
        ));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        assets = new SecureTradeAssetRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, currentPayload, nextPayload) -> { },
                (playerId, itemId, currentPayload, nextPayload) -> { }
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        secure_trade_deliveries,
                        secure_trade_unique_items,
                        secure_trade_commodity_escrow,
                        secure_trade_coin_escrow,
                        secure_trades,
                        pending_unique_deliveries,
                        pending_commodity_deliveries,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
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
    void partialCommodityWithdrawalReturnsOnlyRequestedQuantityAndClearsConfirmation() throws Exception {
        PlayerContext a = playerWithSession("ComWithdrawA", new byte[]{10});
        PlayerContext b = playerWithSession("ComWithdrawB", new byte[]{20});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        SecureTradeCommodityOfferResult offered = assets.addCommodity(
                UUID.randomUUID(), trade.tradeId(), a.session().sessionId(), "paper-a",
                a.session().stateVersion(), COMMODITY, 5, "city", "spawn", new byte[]{5},
                "trade.commodity_add"
        );
        trades.confirm(UUID.randomUUID(), trade.tradeId(), a.playerId());
        UUID operationId = UUID.randomUUID();

        SecureTradeWithdrawalResult first = withdrawals.withdrawCommodity(
                operationId, trade.tradeId(), a.playerId(), COMMODITY, 2, "trade.withdraw"
        );
        SecureTradeWithdrawalResult retry = withdrawals.withdrawCommodity(
                operationId, trade.tradeId(), a.playerId(), COMMODITY, 2, "trade.withdraw"
        );

        assertEquals(first, retry);
        assertEquals(offered.trade().revision() + 1, first.trade().revision());
        assertNull(first.trade().playerAConfirmedRevision());
        assertNull(first.trade().playerBConfirmedRevision());
        assertEquals(3L, assets.commodityEscrow(trade.tradeId(), a.playerId(), COMMODITY));
        assertCommodityDelivery(first.delivery().deliveryId(), a.playerId(), 2);
    }

    @Test
    void fullCommodityWithdrawalRemovesOfferRow() throws Exception {
        PlayerContext a = playerWithSession("CommodityFullA", new byte[]{10});
        PlayerContext b = playerWithSession("CommodityFullB", new byte[]{20});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        assets.addCommodity(
                UUID.randomUUID(), trade.tradeId(), a.session().sessionId(), "paper-a",
                a.session().stateVersion(), COMMODITY, 4, "city", "spawn", new byte[]{6},
                "trade.commodity_add"
        );

        withdrawals.withdrawCommodity(
                UUID.randomUUID(), trade.tradeId(), a.playerId(), COMMODITY, 4, "trade.withdraw"
        );

        assertEquals(0L, assets.commodityEscrow(trade.tradeId(), a.playerId(), COMMODITY));
        assertEquals(0L, commodityEscrowRows(trade.tradeId(), a.playerId()));
    }

    @Test
    void uniqueItemWithdrawalMovesAuthorityToPendingDeliveryAndDeletesActiveOfferRow() throws Exception {
        PlayerContext a = playerWithSession("UniqueWithdrawA", new byte[]{1});
        PlayerContext b = playerWithSession("UniqueWithdrawB", new byte[]{2});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, a.playerId(), "test.item", a.playerId()
        );
        SecureTradeUniqueItemOfferResult offered = assets.addUniqueItem(
                UUID.randomUUID(), trade.tradeId(), a.session().sessionId(), "paper-a",
                a.session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[0], "trade.unique_add"
        );
        UUID operationId = UUID.randomUUID();

        SecureTradeWithdrawalResult first = withdrawals.withdrawUniqueItem(
                operationId,
                trade.tradeId(),
                a.playerId(),
                item.itemInstanceId(),
                offered.escrowItemVersion(),
                "trade.withdraw"
        );
        SecureTradeWithdrawalResult retry = withdrawals.withdrawUniqueItem(
                operationId,
                trade.tradeId(),
                a.playerId(),
                item.itemInstanceId(),
                offered.escrowItemVersion(),
                "trade.withdraw"
        );

        assertEquals(first, retry);
        assertEquals(SecureTradeDeliveryKind.UNIQUE_ITEM, first.delivery().kind());
        assertEquals(a.playerId(), first.delivery().recipientPlayerId());
        assertEquals(0L, uniqueEscrowRows(trade.tradeId(), item.itemInstanceId()));
        assertUniqueDelivery(first.delivery().deliveryId(), a.playerId(), item.itemInstanceId());
        assertItemLocation(
                item.itemInstanceId(),
                "PENDING_DELIVERY",
                first.delivery().deliveryId(),
                offered.escrowItemVersion() + 1
        );
    }

    @Test
    void lockedTradeRejectsOfferWithdrawal() throws Exception {
        PlayerContext a = playerWithSession("LockedWithdrawA", new byte[]{10});
        PlayerContext b = playerWithSession("LockedWithdrawB", new byte[]{20});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        assets.addCommodity(
                UUID.randomUUID(), trade.tradeId(), a.session().sessionId(), "paper-a",
                a.session().stateVersion(), COMMODITY, 2, "city", "spawn", new byte[]{8},
                "trade.commodity_add"
        );
        trades.confirm(UUID.randomUUID(), trade.tradeId(), a.playerId());
        trades.confirm(UUID.randomUUID(), trade.tradeId(), b.playerId());

        assertThrows(
                SecureTradeException.class,
                () -> withdrawals.withdrawCommodity(
                        UUID.randomUUID(), trade.tradeId(), a.playerId(), COMMODITY, 1, "trade.withdraw"
                )
        );
        assertEquals(2L, assets.commodityEscrow(trade.tradeId(), a.playerId(), COMMODITY));
    }

    @Test
    void nonOwnerCannotWithdrawAnotherParticipantsUniqueItem() throws Exception {
        PlayerContext a = playerWithSession("OwnerWithdrawA", new byte[]{1});
        PlayerContext b = playerWithSession("OwnerWithdrawB", new byte[]{2});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, a.playerId(), "test.item", a.playerId()
        );
        SecureTradeUniqueItemOfferResult offered = assets.addUniqueItem(
                UUID.randomUUID(), trade.tradeId(), a.session().sessionId(), "paper-a",
                a.session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[0], "trade.unique_add"
        );

        assertThrows(
                SecureTradeException.class,
                () -> withdrawals.withdrawUniqueItem(
                        UUID.randomUUID(), trade.tradeId(), b.playerId(), item.itemInstanceId(),
                        offered.escrowItemVersion(), "trade.withdraw"
                )
        );
        assertEquals(1L, uniqueEscrowRows(trade.tradeId(), item.itemInstanceId()));
        assertItemLocation(item.itemInstanceId(), "TRADE_ESCROW", trade.tradeId(), offered.escrowItemVersion());
    }

    private PlayerContext playerWithSession(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long version = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "spawn", payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(version, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private long commodityEscrowRows(UUID tradeId, UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM secure_trade_commodity_escrow
                     WHERE trade_id = ? AND owner_player_id = ?
                     """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long uniqueEscrowRows(UUID tradeId, UUID itemId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM secure_trade_unique_items
                     WHERE trade_id = ? AND item_instance_id = ?
                     """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, itemId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private void assertCommodityDelivery(UUID deliveryId, UUID playerId, long quantity) throws SQLException {
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
                assertEquals(COMMODITY, row.getString("commodity_definition_id"));
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

    private void assertItemLocation(UUID itemId, String kind, UUID locationId, long version) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version
                     FROM item_instances
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(kind, row.getString("location_kind"));
                assertEquals(locationId, row.getObject("location_id", UUID.class));
                assertEquals(version, row.getLong("state_version"));
            }
        }
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
