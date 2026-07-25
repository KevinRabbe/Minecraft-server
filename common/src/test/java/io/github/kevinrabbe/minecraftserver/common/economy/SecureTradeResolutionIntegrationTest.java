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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SecureTradeResolutionIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String COMMODITY = "trade.iron";
    private static final String UNIQUE = "trade.sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private CoinWalletRepository wallets;
    private SecureTradeRepository trades;
    private SecureTradeResolutionRepository resolutions;
    private SecureTradeAssetRepository assets;
    private UniqueItemAuthorityRepository items;

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
        wallets = new CoinWalletRepository(dataSource);
        trades = new SecureTradeRepository(dataSource);
        resolutions = new SecureTradeResolutionRepository(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        COMMODITY,
                        "IRON_INGOT",
                        "Trade Iron",
                        64,
                        ItemCategory.MATERIALS,
                        ItemIdentityKind.COMMODITY
                ),
                new ItemDefinition(
                        UNIQUE,
                        "IRON_SWORD",
                        "Trade Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
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
    void coinOnlySettlementConservesValueAndRetryCannotPayTwice() throws Exception {
        UUID a = fundedPlayer("CoinSettleA", 10_000);
        UUID b = fundedPlayer("CoinSettleB", 10_000);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), a, 2_000, "trade.coin_offer");
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), b, 500, "trade.coin_offer");
        trades.confirm(UUID.randomUUID(), trade.tradeId(), a);
        trades.confirm(UUID.randomUUID(), trade.tradeId(), b);
        UUID settleOperation = UUID.randomUUID();

        SecureTradeResolutionResult first = resolutions.settle(
                settleOperation,
                trade.tradeId(),
                "trade.settle"
        );
        SecureTradeResolutionResult retry = resolutions.settle(
                settleOperation,
                trade.tradeId(),
                "trade.settle"
        );

        assertEquals(first, retry);
        assertEquals(SecureTradeStatus.SETTLED, first.trade().status());
        assertEquals(8_500L, wallets.load(a).balanceMinor());
        assertEquals(11_500L, wallets.load(b).balanceMinor());
        assertEquals(20_000L, wallets.load(a).balanceMinor() + wallets.load(b).balanceMinor());
        assertTrue(first.deliveries().isEmpty());
        assertEquals(1L, processedCount(settleOperation));
    }

    @Test
    void cancellationReturnsCoinEscrowExactlyOnce() throws Exception {
        UUID a = fundedPlayer("CoinCancelA", 10_000);
        UUID b = fundedPlayer("CoinCancelB", 10_000);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), a, 2_500, "trade.coin_offer");
        UUID cancelOperation = UUID.randomUUID();

        SecureTradeResolutionResult first = resolutions.cancel(
                cancelOperation,
                trade.tradeId(),
                b,
                "trade.cancel"
        );
        SecureTradeResolutionResult retry = resolutions.cancel(
                cancelOperation,
                trade.tradeId(),
                b,
                "trade.cancel"
        );

        assertEquals(first, retry);
        assertEquals(SecureTradeStatus.CANCELLED, first.trade().status());
        assertEquals(10_000L, wallets.load(a).balanceMinor());
        assertEquals(10_000L, wallets.load(b).balanceMinor());
        assertEquals(1L, processedCount(cancelOperation));
    }

    @Test
    void mixedAssetSettlementDeliversEverythingToOppositeParticipants() throws Exception {
        PlayerContext a = fundedPlayerWithSession("MixedA", 10_000, new byte[]{10});
        PlayerContext b = fundedPlayerWithSession("MixedB", 10_000, new byte[]{20});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        UniqueItemAuthorityResult unique = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, b.playerId(), "test.item", b.playerId()
        );

        SecureTradeCommodityOfferResult commodityOffer = assets.addCommodity(
                UUID.randomUUID(),
                trade.tradeId(),
                a.session().sessionId(),
                "paper-a",
                a.session().stateVersion(),
                COMMODITY,
                4,
                "city",
                "spawn",
                new byte[]{6},
                "trade.commodity_add"
        );
        SessionLease bSession = sessions.heartbeat(b.session().sessionId(), "paper-a", LEASE);
        SecureTradeUniqueItemOfferResult uniqueOffer = assets.addUniqueItem(
                UUID.randomUUID(),
                trade.tradeId(),
                bSession.sessionId(),
                "paper-a",
                bSession.stateVersion(),
                unique.itemInstanceId(),
                unique.stateVersion(),
                "city",
                "spawn",
                new byte[]{19},
                "trade.unique_add"
        );
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), a.playerId(), 1_000, "trade.coin_offer");
        trades.confirm(UUID.randomUUID(), trade.tradeId(), a.playerId());
        trades.confirm(UUID.randomUUID(), trade.tradeId(), b.playerId());

        SecureTradeResolutionResult settled = resolutions.settle(
                UUID.randomUUID(), trade.tradeId(), "trade.settle"
        );

        assertEquals(SecureTradeStatus.SETTLED, settled.trade().status());
        assertEquals(2, settled.deliveries().size());
        SecureTradeDeliverySnapshot commodityDelivery = settled.deliveries().stream()
                .filter(delivery -> delivery.kind() == SecureTradeDeliveryKind.COMMODITY)
                .findFirst()
                .orElseThrow();
        SecureTradeDeliverySnapshot uniqueDelivery = settled.deliveries().stream()
                .filter(delivery -> delivery.kind() == SecureTradeDeliveryKind.UNIQUE_ITEM)
                .findFirst()
                .orElseThrow();

        assertEquals(a.playerId(), commodityDelivery.sourceOwnerPlayerId());
        assertEquals(b.playerId(), commodityDelivery.recipientPlayerId());
        assertEquals(4L, commodityDelivery.quantity());
        assertEquals(b.playerId(), uniqueDelivery.sourceOwnerPlayerId());
        assertEquals(a.playerId(), uniqueDelivery.recipientPlayerId());
        assertEquals(unique.itemInstanceId(), uniqueDelivery.itemInstanceId());
        assertCommodityDelivery(commodityDelivery.deliveryId(), b.playerId(), COMMODITY, 4);
        assertUniquePendingDelivery(uniqueDelivery.deliveryId(), a.playerId(), unique.itemInstanceId());
        assertItemLocation(unique.itemInstanceId(), "PENDING_DELIVERY", uniqueDelivery.deliveryId(), uniqueOffer.escrowItemVersion() + 1);
        assertEquals(11_000L, wallets.load(b.playerId()).balanceMinor());
        assertEquals(9_000L, wallets.load(a.playerId()).balanceMinor());
        assertEquals(4L, commodityOffer.escrowQuantity());
    }

    @Test
    void cancellationReturnsCommodityAndUniqueItemToOriginalOwnersViaPendingDelivery() throws Exception {
        PlayerContext a = fundedPlayerWithSession("ReturnA", 10_000, new byte[]{10});
        PlayerContext b = fundedPlayerWithSession("ReturnB", 10_000, new byte[]{20});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        UniqueItemAuthorityResult unique = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, b.playerId(), "test.item", b.playerId()
        );

        assets.addCommodity(
                UUID.randomUUID(), trade.tradeId(), a.session().sessionId(), "paper-a",
                a.session().stateVersion(), COMMODITY, 3, "city", "spawn", new byte[]{7},
                "trade.commodity_add"
        );
        SessionLease bSession = sessions.heartbeat(b.session().sessionId(), "paper-a", LEASE);
        SecureTradeUniqueItemOfferResult itemOffer = assets.addUniqueItem(
                UUID.randomUUID(), trade.tradeId(), bSession.sessionId(), "paper-a",
                bSession.stateVersion(), unique.itemInstanceId(), unique.stateVersion(),
                "city", "spawn", new byte[]{19}, "trade.unique_add"
        );

        SecureTradeResolutionResult cancelled = resolutions.cancel(
                UUID.randomUUID(), trade.tradeId(), a.playerId(), "trade.cancel"
        );

        assertEquals(SecureTradeStatus.CANCELLED, cancelled.trade().status());
        SecureTradeDeliverySnapshot commodityReturn = cancelled.deliveries().stream()
                .filter(delivery -> delivery.kind() == SecureTradeDeliveryKind.COMMODITY)
                .findFirst().orElseThrow();
        SecureTradeDeliverySnapshot uniqueReturn = cancelled.deliveries().stream()
                .filter(delivery -> delivery.kind() == SecureTradeDeliveryKind.UNIQUE_ITEM)
                .findFirst().orElseThrow();
        assertEquals(a.playerId(), commodityReturn.recipientPlayerId());
        assertEquals(b.playerId(), uniqueReturn.recipientPlayerId());
        assertUniquePendingDelivery(uniqueReturn.deliveryId(), b.playerId(), unique.itemInstanceId());
        assertItemLocation(unique.itemInstanceId(), "PENDING_DELIVERY", uniqueReturn.deliveryId(), itemOffer.escrowItemVersion() + 1);
    }

    @Test
    void lockedTradeCannotBeCancelled() throws Exception {
        UUID a = fundedPlayer("LockedCancelA", 10_000);
        UUID b = fundedPlayer("LockedCancelB", 10_000);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), a, 100, "trade.coin_offer");
        trades.confirm(UUID.randomUUID(), trade.tradeId(), a);
        trades.confirm(UUID.randomUUID(), trade.tradeId(), b);

        assertThrows(
                SecureTradeException.class,
                () -> resolutions.cancel(UUID.randomUUID(), trade.tradeId(), a, "trade.cancel")
        );
        assertEquals(SecureTradeStatus.LOCKED, trades.load(trade.tradeId()).status());
    }

    @Test
    void concurrentDifferentSettlementOperationsPayOnlyOnce() throws Exception {
        UUID a = fundedPlayer("RaceSettleA", 10_000);
        UUID b = fundedPlayer("RaceSettleB", 10_000);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), a, 1_000, "trade.coin_offer");
        trades.confirm(UUID.randomUUID(), trade.tradeId(), a);
        trades.confirm(UUID.randomUUID(), trade.tradeId(), b);

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SecureTradeResolutionResult> first = executor.submit(
                    () -> resolutions.settle(UUID.randomUUID(), trade.tradeId(), "trade.settle")
            );
            Future<SecureTradeResolutionResult> second = executor.submit(
                    () -> resolutions.settle(UUID.randomUUID(), trade.tradeId(), "trade.settle")
            );
            for (Future<SecureTradeResolutionResult> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof SecureTradeException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(9_000L, wallets.load(a).balanceMinor());
        assertEquals(11_000L, wallets.load(b).balanceMinor());
        assertEquals(SecureTradeStatus.SETTLED, trades.load(trade.tradeId()).status());
    }

    @Test
    void databaseRejectsReleasingTradeItemWithoutOfferRemovalOrTerminalTrade() throws Exception {
        PlayerContext a = fundedPlayerWithSession("ReverseCustodyA", 10_000, new byte[]{1});
        PlayerContext b = fundedPlayerWithSession("ReverseCustodyB", 10_000, new byte[]{2});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        UniqueItemAuthorityResult unique = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, a.playerId(), "test.item", a.playerId()
        );
        SecureTradeUniqueItemOfferResult offer = assets.addUniqueItem(
                UUID.randomUUID(), trade.tradeId(), a.session().sessionId(), "paper-a",
                a.session().stateVersion(), unique.itemInstanceId(), unique.stateVersion(),
                "city", "spawn", new byte[0], "trade.unique_add"
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE item_instances
                    SET location_kind = 'QUARANTINE',
                        location_id = NULL,
                        state_version = state_version + 1
                    WHERE item_instance_id = ?
                    """)) {
                statement.setObject(1, unique.itemInstanceId());
                statement.executeUpdate();
            }
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }

        assertItemLocation(unique.itemInstanceId(), "TRADE_ESCROW", trade.tradeId(), offer.escrowItemVersion());
    }

    private UUID fundedPlayer(String name, long amount) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, amount, "test.funding");
        return playerId;
    }

    private PlayerContext fundedPlayerWithSession(String name, long amount, byte[] payload) throws SQLException {
        UUID playerId = fundedPlayer(name, amount);
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long stateVersion = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "spawn", payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(stateVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
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

    private void assertUniquePendingDelivery(UUID deliveryId, UUID playerId, UUID itemId) throws SQLException {
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

    private long processedCount(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM processed_operations
                     WHERE operation_id = ?
                     """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
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
