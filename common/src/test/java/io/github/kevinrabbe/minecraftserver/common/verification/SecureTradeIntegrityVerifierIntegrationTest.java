package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeAssetRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeResolutionRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SecureTradeSnapshot;
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
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SecureTradeIntegrityVerifierIntegrationTest {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String COMMODITY = "integrity.trade_iron";
    private static final String UNIQUE = "integrity.trade_sword";

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
    private SecureTradeIntegrityVerifier verifier;

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
                        "Integrity Trade Iron",
                        64,
                        ItemCategory.MATERIALS,
                        ItemIdentityKind.COMMODITY
                ),
                new ItemDefinition(
                        UNIQUE,
                        "IRON_SWORD",
                        "Integrity Trade Sword",
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
        verifier = new SecureTradeIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        truncateAuthority();
    }

    @AfterEach
    void cleanDatabase() throws SQLException {
        truncateAuthority();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void realMixedSettlementAndCancellationRemainClean() throws Exception {
        PlayerContext settleA = fundedPlayerWithSession("StSettleA", 10_000L, new byte[]{1});
        PlayerContext settleB = fundedPlayerWithSession("StSettleB", 10_000L, new byte[]{2});
        SecureTradeSnapshot settledTrade = trades.createTrade(
                UUID.randomUUID(), settleA.playerId(), settleB.playerId()
        );
        UniqueItemAuthorityResult settleItem = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, settleB.playerId(), "test.trade_item", settleB.playerId()
        );
        assets.addCommodity(
                UUID.randomUUID(), settledTrade.tradeId(), settleA.session().sessionId(), "paper-a",
                settleA.session().stateVersion(), COMMODITY, 4L, "city", "trade",
                new byte[]{3}, "trade.integrity_commodity"
        );
        assets.addUniqueItem(
                UUID.randomUUID(), settledTrade.tradeId(), settleB.session().sessionId(), "paper-a",
                settleB.session().stateVersion(), settleItem.itemInstanceId(), settleItem.stateVersion(),
                "city", "trade", new byte[]{4}, "trade.integrity_unique"
        );
        trades.setCoinOffer(
                UUID.randomUUID(), settledTrade.tradeId(), settleA.playerId(), 1_000L, "trade.integrity_coin"
        );
        trades.confirm(UUID.randomUUID(), settledTrade.tradeId(), settleA.playerId());
        trades.confirm(UUID.randomUUID(), settledTrade.tradeId(), settleB.playerId());
        resolutions.settle(UUID.randomUUID(), settledTrade.tradeId(), "trade.integrity_settle");

        PlayerContext cancelA = fundedPlayerWithSession("StCancelA", 10_000L, new byte[]{5});
        PlayerContext cancelB = fundedPlayerWithSession("StCancelB", 10_000L, new byte[]{6});
        SecureTradeSnapshot cancelledTrade = trades.createTrade(
                UUID.randomUUID(), cancelA.playerId(), cancelB.playerId()
        );
        UniqueItemAuthorityResult cancelItem = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, cancelB.playerId(), "test.trade_item", cancelB.playerId()
        );
        assets.addCommodity(
                UUID.randomUUID(), cancelledTrade.tradeId(), cancelA.session().sessionId(), "paper-a",
                cancelA.session().stateVersion(), COMMODITY, 3L, "city", "trade",
                new byte[]{7}, "trade.integrity_commodity"
        );
        assets.addUniqueItem(
                UUID.randomUUID(), cancelledTrade.tradeId(), cancelB.session().sessionId(), "paper-a",
                cancelB.session().stateVersion(), cancelItem.itemInstanceId(), cancelItem.stateVersion(),
                "city", "trade", new byte[]{8}, "trade.integrity_unique"
        );
        trades.setCoinOffer(
                UUID.randomUUID(), cancelledTrade.tradeId(), cancelA.playerId(), 750L, "trade.integrity_coin"
        );
        resolutions.cancel(
                UUID.randomUUID(), cancelledTrade.tradeId(), cancelB.playerId(), "trade.integrity_cancel"
        );

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void missingTerminalProcessedOperationIsDetectedOnce() throws Exception {
        UUID a = fundedPlayer("StOpLossA", 10_000L);
        UUID b = fundedPlayer("StOpLossB", 10_000L);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), a, 1_000L, "trade.integrity_coin");
        trades.confirm(UUID.randomUUID(), trade.tradeId(), a);
        trades.confirm(UUID.randomUUID(), trade.tradeId(), b);
        resolutions.settle(UUID.randomUUID(), trade.tradeId(), "trade.integrity_settle");

        execute("TRUNCATE TABLE processed_operations");

        assertContainsOnly("SECURE_TRADE_TERMINAL_OPERATION_MISMATCH", trade.tradeId());
    }

    @Test
    void missingTerminalLedgerCreditIsDetectedIndependently() throws Exception {
        UUID a = fundedPlayer("StLedgerA", 10_000L);
        UUID b = fundedPlayer("StLedgerB", 10_000L);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b);
        trades.setCoinOffer(UUID.randomUUID(), trade.tradeId(), a, 1_000L, "trade.integrity_coin");
        trades.confirm(UUID.randomUUID(), trade.tradeId(), a);
        trades.confirm(UUID.randomUUID(), trade.tradeId(), b);
        resolutions.settle(UUID.randomUUID(), trade.tradeId(), "trade.integrity_settle");

        execute("TRUNCATE TABLE economic_ledger");

        assertContainsOnly("SECURE_TRADE_LEDGER_EVIDENCE_MISMATCH", trade.tradeId());
    }

    @Test
    void missingCommodityPendingIssuanceIsDetectedIndependently() throws Exception {
        PlayerContext a = fundedPlayerWithSession("StCommA", 10_000L, new byte[]{1});
        UUID b = fundedPlayer("StCommB", 10_000L);
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a.playerId(), b);
        assets.addCommodity(
                UUID.randomUUID(), trade.tradeId(), a.session().sessionId(), "paper-a",
                a.session().stateVersion(), COMMODITY, 5L, "city", "trade",
                new byte[]{2}, "trade.integrity_commodity"
        );
        trades.confirm(UUID.randomUUID(), trade.tradeId(), a.playerId());
        trades.confirm(UUID.randomUUID(), trade.tradeId(), b);
        resolutions.settle(UUID.randomUUID(), trade.tradeId(), "trade.integrity_settle");

        execute("TRUNCATE TABLE pending_commodity_deliveries");

        assertContainsOnly("SECURE_TRADE_DELIVERY_EVIDENCE_MISMATCH", trade.tradeId());
    }

    @Test
    void missingUniqueSettlementProvenanceIsDetectedIndependently() throws Exception {
        UUID a = fundedPlayer("StUniqueA", 10_000L);
        PlayerContext b = fundedPlayerWithSession("StUniqueB", 10_000L, new byte[]{1});
        SecureTradeSnapshot trade = trades.createTrade(UUID.randomUUID(), a, b.playerId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, b.playerId(), "test.trade_item", b.playerId()
        );
        assets.addUniqueItem(
                UUID.randomUUID(), trade.tradeId(), b.session().sessionId(), "paper-a",
                b.session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "trade", new byte[]{2}, "trade.integrity_unique"
        );
        trades.confirm(UUID.randomUUID(), trade.tradeId(), a);
        trades.confirm(UUID.randomUUID(), trade.tradeId(), b.playerId());
        resolutions.settle(UUID.randomUUID(), trade.tradeId(), "trade.integrity_settle");

        execute("TRUNCATE TABLE item_provenance");

        assertContainsOnly("SECURE_TRADE_DELIVERY_EVIDENCE_MISMATCH", trade.tradeId());
    }

    private UUID fundedPlayer(String name, long amountMinor) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, amountMinor, "test.trade_integrity_funding");
        return playerId;
    }

    private PlayerContext fundedPlayerWithSession(String name, long amountMinor, byte[] payload) throws SQLException {
        UUID playerId = fundedPlayer(name, amountMinor);
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long stateVersion = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "trade", payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(stateVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void assertContainsOnly(String expectedCode, UUID expectedTradeId) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals(expectedCode, issue.code());
        assertEquals(expectedTradeId.toString(), issue.subjectId());
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void truncateAuthority() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
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
