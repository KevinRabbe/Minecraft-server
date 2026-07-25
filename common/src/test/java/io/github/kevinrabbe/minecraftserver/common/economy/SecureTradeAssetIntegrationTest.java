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
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateSnapshot;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SecureTradeAssetIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String COMMODITY = "trade.iron";
    private static final String UNIQUE = "trade.sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private SecureTradeRepository trades;
    private UniqueItemAuthorityRepository items;
    private ItemCatalog catalog;

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
        catalog = new ItemCatalog(List.of(
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
    void commodityEscrowCommitsOnlyExactValidatedSerializedRemoval() throws Exception {
        PlayerContext a = playerWithSession("CommodityA", new byte[]{10});
        PlayerContext b = playerWithSession("CommodityB", new byte[]{20});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        AtomicInteger validations = new AtomicInteger();
        SecureTradeAssetRepository assets = new SecureTradeAssetRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, currentPayload, nextPayload) -> {
                    validations.incrementAndGet();
                    assertEquals(a.playerId(), playerId);
                    assertEquals(COMMODITY, definitionId);
                    assertEquals(3L, quantity);
                    assertArrayEquals(new byte[]{10}, currentPayload);
                    assertArrayEquals(new byte[]{7}, nextPayload);
                },
                (playerId, itemId, currentPayload, nextPayload) -> { }
        );
        UUID operationId = UUID.randomUUID();

        SecureTradeCommodityOfferResult first = assets.addCommodity(
                operationId,
                trade.tradeId(),
                a.session().sessionId(),
                "paper-a",
                a.session().stateVersion(),
                COMMODITY,
                3,
                "city",
                "spawn",
                new byte[]{7},
                "trade.commodity_add"
        );
        SecureTradeCommodityOfferResult retry = assets.addCommodity(
                operationId,
                trade.tradeId(),
                a.session().sessionId(),
                "paper-a",
                a.session().stateVersion(),
                COMMODITY,
                3,
                "city",
                "spawn",
                new byte[]{7},
                "trade.commodity_add"
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertEquals(3L, assets.commodityEscrow(trade.tradeId(), a.playerId(), COMMODITY));
        assertEquals(1L, first.trade().revision());
        PlayerStateSnapshot persisted = states.load(a.playerId());
        assertEquals(first.playerStateVersion(), persisted.stateVersion());
        assertArrayEquals(new byte[]{7}, persisted.statePayload());
    }

    @Test
    void commodityValidatorFailureRollsBackStateEscrowLedgerAndRevision() throws Exception {
        PlayerContext a = playerWithSession("InvalidCommodityA", new byte[]{10});
        PlayerContext b = playerWithSession("InvalidCommodityB", new byte[]{20});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        SecureTradeAssetRepository assets = new SecureTradeAssetRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, currentPayload, nextPayload) -> {
                    throw new SecureTradeException("invalid commodity state transition");
                },
                (playerId, itemId, currentPayload, nextPayload) -> { }
        );

        assertThrows(
                SecureTradeException.class,
                () -> assets.addCommodity(
                        UUID.randomUUID(),
                        trade.tradeId(),
                        a.session().sessionId(),
                        "paper-a",
                        a.session().stateVersion(),
                        COMMODITY,
                        3,
                        "city",
                        "spawn",
                        new byte[]{9},
                        "trade.commodity_add"
                )
        );

        assertEquals(0L, assets.commodityEscrow(trade.tradeId(), a.playerId(), COMMODITY));
        assertEquals(0L, trades.load(trade.tradeId()).revision());
        assertEquals(a.session().stateVersion(), states.load(a.playerId()).stateVersion());
        assertArrayEquals(new byte[]{10}, states.load(a.playerId()).statePayload());
    }

    @Test
    void uniqueItemMovesToTradeEscrowOnlyWithMatchingStateAndItemVersion() throws Exception {
        PlayerContext a = playerWithSession("UniqueA", new byte[]{1, 2});
        PlayerContext b = playerWithSession("UniqueB", new byte[]{3, 4});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, a.playerId(), "test.item", a.playerId()
        );
        AtomicInteger validations = new AtomicInteger();
        SecureTradeAssetRepository assets = new SecureTradeAssetRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, currentPayload, nextPayload) -> { },
                (playerId, itemId, currentPayload, nextPayload) -> {
                    validations.incrementAndGet();
                    assertEquals(a.playerId(), playerId);
                    assertEquals(item.itemInstanceId(), itemId);
                    assertArrayEquals(new byte[]{1, 2}, currentPayload);
                    assertArrayEquals(new byte[]{1}, nextPayload);
                }
        );
        UUID operationId = UUID.randomUUID();

        SecureTradeUniqueItemOfferResult first = assets.addUniqueItem(
                operationId,
                trade.tradeId(),
                a.session().sessionId(),
                "paper-a",
                a.session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "spawn",
                new byte[]{1},
                "trade.unique_add"
        );
        SecureTradeUniqueItemOfferResult retry = assets.addUniqueItem(
                operationId,
                trade.tradeId(),
                a.session().sessionId(),
                "paper-a",
                a.session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "spawn",
                new byte[]{1},
                "trade.unique_add"
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertEquals(1L, first.trade().revision());
        assertItemCustody(item.itemInstanceId(), "TRADE_ESCROW", trade.tradeId(), item.stateVersion() + 1);
        assertEquals(1L, secureTradeItemCount(trade.tradeId(), item.itemInstanceId()));
        assertArrayEquals(new byte[]{1}, states.load(a.playerId()).statePayload());
    }

    @Test
    void staleUniqueItemVersionFailsBeforePlayerStateMutation() throws Exception {
        PlayerContext a = playerWithSession("StaleUniqueA", new byte[]{1, 2});
        PlayerContext b = playerWithSession("StaleUniqueB", new byte[]{3, 4});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, a.playerId(), "test.item", a.playerId()
        );
        SecureTradeAssetRepository assets = permissiveAssets();

        assertThrows(
                SecureTradeException.class,
                () -> assets.addUniqueItem(
                        UUID.randomUUID(),
                        trade.tradeId(),
                        a.session().sessionId(),
                        "paper-a",
                        a.session().stateVersion(),
                        item.itemInstanceId(),
                        item.stateVersion() + 1,
                        "city",
                        "spawn",
                        new byte[]{1},
                        "trade.unique_add"
                )
        );

        assertItemCustody(item.itemInstanceId(), "PLAYER_INVENTORY", a.playerId(), item.stateVersion());
        assertArrayEquals(new byte[]{1, 2}, states.load(a.playerId()).statePayload());
        assertEquals(0L, trades.load(trade.tradeId()).revision());
    }

    @Test
    void nonParticipantSessionCannotMoveCommodityOrUniqueItem() throws Exception {
        PlayerContext a = playerWithSession("ParticipantA", new byte[]{10});
        PlayerContext b = playerWithSession("ParticipantB", new byte[]{20});
        PlayerContext outsider = playerWithSession("ParticipantOutsider", new byte[]{30});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, outsider.playerId(), "test.item", outsider.playerId()
        );
        SecureTradeAssetRepository assets = permissiveAssets();

        assertThrows(
                SecureTradeException.class,
                () -> assets.addCommodity(
                        UUID.randomUUID(),
                        trade.tradeId(),
                        outsider.session().sessionId(),
                        "paper-a",
                        outsider.session().stateVersion(),
                        COMMODITY,
                        1,
                        "city",
                        "spawn",
                        new byte[]{29},
                        "trade.commodity_add"
                )
        );
        assertThrows(
                SecureTradeException.class,
                () -> assets.addUniqueItem(
                        UUID.randomUUID(),
                        trade.tradeId(),
                        outsider.session().sessionId(),
                        "paper-a",
                        outsider.session().stateVersion(),
                        item.itemInstanceId(),
                        item.stateVersion(),
                        "city",
                        "spawn",
                        new byte[]{29},
                        "trade.unique_add"
                )
        );
        assertItemCustody(item.itemInstanceId(), "PLAYER_INVENTORY", outsider.playerId(), item.stateVersion());
    }

    @Test
    void assetChangeClearsExistingConfirmation() throws Exception {
        PlayerContext a = playerWithSession("ConfirmA", new byte[]{10});
        PlayerContext b = playerWithSession("ConfirmB", new byte[]{20});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), a.playerId(), 100, "trade.coin_offer");
        SecureTradeSnapshot confirmed = trades.confirm(UUID.randomUUID(), trade.tradeId(), a.playerId());
        assertEquals(Long.valueOf(1), confirmed.playerAConfirmedRevision());

        SecureTradeCommodityOfferResult result = permissiveAssets().addCommodity(
                UUID.randomUUID(),
                trade.tradeId(),
                b.session().sessionId(),
                "paper-a",
                b.session().stateVersion(),
                COMMODITY,
                2,
                "city",
                "spawn",
                new byte[]{18},
                "trade.commodity_add"
        );

        assertEquals(2L, result.trade().revision());
        assertEquals(null, result.trade().playerAConfirmedRevision());
        assertEquals(null, result.trade().playerBConfirmedRevision());
    }

    private SecureTradeAssetRepository permissiveAssets() {
        return new SecureTradeAssetRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, currentPayload, nextPayload) -> { },
                (playerId, itemId, currentPayload, nextPayload) -> { }
        );
    }

    private PlayerContext playerWithSession(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long stateVersion = states.commit(
                session.sessionId(),
                "paper-a",
                session.stateVersion(),
                "city",
                "spawn",
                payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(stateVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void assertItemCustody(UUID itemId, String kind, UUID locationId, long version) throws SQLException {
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

    private long secureTradeItemCount(UUID tradeId, UUID itemId) throws SQLException {
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

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerContext(UUID playerId, SessionLease session) {
        private PlayerContext {
            payloadSafety(playerId, session);
        }

        private static void payloadSafety(UUID playerId, SessionLease session) {
            if (!session.playerId().equals(playerId)) {
                throw new IllegalArgumentException("session/player mismatch");
            }
        }
    }
}
