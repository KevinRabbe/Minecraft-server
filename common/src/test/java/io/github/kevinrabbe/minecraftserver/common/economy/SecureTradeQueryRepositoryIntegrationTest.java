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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SecureTradeQueryRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String COMMODITY = "trade.query_iron";
    private static final String UNIQUE = "trade.query_sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private CoinWalletRepository wallets;
    private SecureTradeRepository trades;
    private UniqueItemAuthorityRepository items;
    private SecureTradeAssetRepository assets;
    private SecureTradeQueryRepository queries;

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
        wallets = new CoinWalletRepository(dataSource);
        trades = new SecureTradeRepository(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        COMMODITY,
                        "IRON_INGOT",
                        "Query Iron",
                        64,
                        ItemCategory.MATERIALS,
                        ItemIdentityKind.COMMODITY
                ),
                new ItemDefinition(
                        UNIQUE,
                        "IRON_SWORD",
                        "Query Sword",
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
                (playerId, itemInstanceId, currentPayload, nextPayload) -> { }
        );
        queries = new SecureTradeQueryRepository(dataSource);
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
    void loadOfferReturnsOneConsistentCompleteRevision() throws Exception {
        PlayerContext a = playerWithSession("QueryTradeA", new byte[]{10});
        PlayerContext b = playerWithSession("QueryTradeB", new byte[]{20});
        wallets.creditFromSystem(UUID.randomUUID(), a.playerId(), 5_000, "test.funding");
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b.playerId());

        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), a.playerId(), 1_250, "trade.coin_offer");
        SecureTradeCommodityOfferResult commodity = assets.addCommodity(
                UUID.randomUUID(),
                trade.tradeId(),
                b.session().sessionId(),
                "paper-a",
                b.session().stateVersion(),
                COMMODITY,
                4,
                "city",
                "spawn",
                new byte[]{16},
                "trade.commodity_add"
        );
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, a.playerId(), "test.item", a.playerId()
        );
        SecureTradeUniqueItemOfferResult unique = assets.addUniqueItem(
                UUID.randomUUID(),
                trade.tradeId(),
                a.session().sessionId(),
                "paper-a",
                a.session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "spawn",
                new byte[]{9},
                "trade.unique_add"
        );

        SecureTradeOfferView view = queries.loadOffer(trade.tradeId());

        assertEquals(unique.trade(), view.trade());
        assertEquals(Map.of(a.playerId(), 1_250L), view.coinOffersMinor());
        assertEquals(List.of(new SecureTradeCommodityOffer(b.playerId(), COMMODITY, 4)), view.commodityOffers());
        assertEquals(1, view.uniqueOffers().size());
        SecureTradeUniqueOffer visibleItem = view.uniqueOffers().getFirst();
        assertEquals(a.playerId(), visibleItem.ownerPlayerId());
        assertEquals(item.itemInstanceId(), visibleItem.itemInstanceId());
        assertEquals(unique.escrowItemVersion(), visibleItem.escrowItemVersion());
        assertEquals(UNIQUE, visibleItem.definitionId());
        assertEquals(Map.of(), visibleItem.rollQualityBasisPoints());
        assertEquals(commodity.trade().revision() + 1, view.trade().revision());
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

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerContext(UUID playerId, SessionLease session) { }
}
