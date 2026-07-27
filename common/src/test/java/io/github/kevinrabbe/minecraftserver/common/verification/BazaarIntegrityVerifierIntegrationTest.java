package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderRequest;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarOrderSide;
import io.github.kevinrabbe.minecraftserver.common.economy.BazaarRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BazaarIntegrityVerifierIntegrationTest {
    private static final String COMMODITY = "integrity.bazaar_iron";
    private static final Duration LEASE = Duration.ofMinutes(5);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private CoinWalletRepository wallets;
    private BazaarRepository bazaar;
    private BazaarIntegrityVerifier verifier;

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
        wallets = new CoinWalletRepository(dataSource);
        ItemCatalog items = new ItemCatalog(List.of(new ItemDefinition(
                COMMODITY,
                "RAW_IRON",
                "Integrity Bazaar Iron",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
        bazaar = new BazaarRepository(dataSource, items, (player, definition, quantity, before, after) -> { }, 100);
        verifier = new BazaarIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        truncateBazaarAuthority();
    }

    @AfterEach
    void cleanDatabase() throws SQLException {
        truncateBazaarAuthority();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void realPartialFillBothCancellationSidesAndMatchPassesAreClean() throws Exception {
        UUID buyer = player("BazaarCheckBuy");
        UUID seller = player("BazaarCheckSell");
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 2_000L, "test.bazaar_integrity_seed");

        var buy = bazaar.createBuyOrder(
                UUID.randomUUID(),
                buyer,
                new BazaarOrderRequest(COMMODITY, BazaarOrderSide.BUY, 3L, 100L),
                "bazaar.integrity_buy"
        );
        var sell = createSellOrder(seller, 5L, 90L, "bazaar.integrity_sell");

        var matched = bazaar.matchCommodity(UUID.randomUUID(), COMMODITY, 10, "bazaar.integrity_match");
        assertEquals(1, matched.fills());
        assertEquals(3L, matched.quantityFilled());
        assertEquals(0L, bazaar.loadOrder(buy.orderId()).remainingQuantity());
        assertEquals(2L, bazaar.loadOrder(sell.orderId()).remainingQuantity());

        bazaar.cancelOrder(UUID.randomUUID(), sell.orderId(), seller, "bazaar.integrity_cancel_sell");

        var cancelledBuy = bazaar.createBuyOrder(
                UUID.randomUUID(),
                buyer,
                new BazaarOrderRequest(COMMODITY, BazaarOrderSide.BUY, 2L, 70L),
                "bazaar.integrity_buy_cancel"
        );
        bazaar.cancelOrder(UUID.randomUUID(), cancelledBuy.orderId(), buyer, "bazaar.integrity_cancel_buy");

        var emptyMatch = bazaar.matchCommodity(UUID.randomUUID(), COMMODITY, 10, "bazaar.integrity_empty_match");
        assertEquals(0, emptyMatch.fills());

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void missingCreateOperationEvidenceIsDetected() throws Exception {
        UUID buyer = player("BazaarCreate");
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 1_000L, "test.bazaar_integrity_seed");
        var created = bazaar.createBuyOrder(
                UUID.randomUUID(),
                buyer,
                new BazaarOrderRequest(COMMODITY, BazaarOrderSide.BUY, 2L, 100L),
                "bazaar.integrity_buy"
        );
        truncateProcessedOperations();

        assertContainsOnly("BAZAAR_CREATE_EVIDENCE_MISMATCH", created.orderId());
    }

    @Test
    void corruptedFillDeliveryIsDetected() throws Exception {
        UUID buyer = player("BazaarFillBuy");
        UUID seller = player("BazaarFillSell");
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 1_000L, "test.bazaar_integrity_seed");
        bazaar.createBuyOrder(
                UUID.randomUUID(),
                buyer,
                new BazaarOrderRequest(COMMODITY, BazaarOrderSide.BUY, 2L, 100L),
                "bazaar.integrity_buy"
        );
        createSellOrder(seller, 2L, 100L, "bazaar.integrity_sell");
        bazaar.matchCommodity(UUID.randomUUID(), COMMODITY, 10, "bazaar.integrity_match");

        UUID fillId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement fill = connection.prepareStatement("""
                     SELECT fill_id, fill_operation_id FROM bazaar_fills LIMIT 1
                     """)) {
            try (var row = fill.executeQuery()) {
                assertTrue(row.next());
                fillId = row.getObject("fill_id", UUID.class);
                UUID fillOperation = row.getObject("fill_operation_id", UUID.class);
                try (PreparedStatement corrupt = connection.prepareStatement("""
                        UPDATE pending_commodity_deliveries
                        SET quantity = quantity + 1
                        WHERE source_operation_id = ?
                        """)) {
                    corrupt.setObject(1, fillOperation);
                    assertEquals(1, corrupt.executeUpdate());
                }
            }
        }

        assertContainsOnly("BAZAAR_FILL_EVIDENCE_MISMATCH", fillId);
    }

    @Test
    void mutableOrderRemainderDriftIsDetectedFromFillHistory() throws Exception {
        UUID buyer = player("BazaarStateBuy");
        UUID seller = player("BazaarStateSell");
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 2_000L, "test.bazaar_integrity_seed");
        bazaar.createBuyOrder(
                UUID.randomUUID(),
                buyer,
                new BazaarOrderRequest(COMMODITY, BazaarOrderSide.BUY, 3L, 100L),
                "bazaar.integrity_buy"
        );
        var sell = createSellOrder(seller, 5L, 90L, "bazaar.integrity_sell");
        bazaar.matchCommodity(UUID.randomUUID(), COMMODITY, 10, "bazaar.integrity_match");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement corrupt = connection.prepareStatement("""
                     UPDATE bazaar_orders SET remaining_quantity = 1 WHERE order_id = ?
                     """)) {
            corrupt.setObject(1, sell.orderId());
            assertEquals(1, corrupt.executeUpdate());
        }

        assertContainsOnly("BAZAAR_ORDER_STATE_MISMATCH", sell.orderId());
    }

    @Test
    void cancellationOperationRebindingIsDetected() throws Exception {
        UUID buyer = player("BazaarCancel");
        wallets.creditFromSystem(UUID.randomUUID(), buyer, 1_000L, "test.bazaar_integrity_seed");
        var buy = bazaar.createBuyOrder(
                UUID.randomUUID(),
                buyer,
                new BazaarOrderRequest(COMMODITY, BazaarOrderSide.BUY, 2L, 100L),
                "bazaar.integrity_buy"
        );
        bazaar.cancelOrder(UUID.randomUUID(), buy.orderId(), buyer, "bazaar.integrity_cancel_buy");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement corrupt = connection.prepareStatement("""
                     UPDATE bazaar_orders SET cancel_operation_id = ? WHERE order_id = ?
                     """)) {
            corrupt.setObject(1, UUID.randomUUID());
            corrupt.setObject(2, buy.orderId());
            assertEquals(1, corrupt.executeUpdate());
        }

        assertContainsOnly("BAZAAR_CANCEL_EVIDENCE_MISMATCH", buy.orderId());
    }

    @Test
    void malformedMatchAggregateIsDetectedWithoutInventingPerFillLinkage() throws Exception {
        UUID operationId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO processed_operations(operation_id, operation_type, result)
                     VALUES (?, 'BAZAAR_MATCH', jsonb_build_object(
                         'commodity_definition_id', ?,
                         'fills', 2,
                         'quantity_filled', 2,
                         'gross_trade_value_minor', 200,
                         'fees_destroyed_minor', 2,
                         'max_fills', 1,
                         'reason', 'bazaar.integrity_forged_match'
                     ))
                     """)) {
            statement.setObject(1, operationId);
            statement.setString(2, COMMODITY);
            assertEquals(1, statement.executeUpdate());
        }

        assertContainsOnly("BAZAAR_MATCH_EVIDENCE_MISMATCH", operationId);
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private io.github.kevinrabbe.minecraftserver.common.economy.BazaarSellOrderCreateResult createSellOrder(
            UUID seller,
            long quantity,
            long priceMinor,
            String reason
    ) throws SQLException {
        String backend = "bazaar-integrity";
        SessionLease lease = sessions.openSession(seller, backend, null, LEASE);
        try {
            return bazaar.createSellOrder(
                    UUID.randomUUID(),
                    lease.sessionId(),
                    backend,
                    lease.stateVersion(),
                    new BazaarOrderRequest(COMMODITY, BazaarOrderSide.SELL, quantity, priceMinor),
                    "city",
                    "market",
                    new byte[]{1},
                    reason
            );
        } finally {
            sessions.disconnect(lease.sessionId(), backend);
        }
    }

    private void assertContainsOnly(String expectedCode, UUID expectedSubject) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals(expectedCode, issue.code());
        assertEquals(expectedSubject.toString(), issue.subjectId());
    }

    private void truncateProcessedOperations() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE processed_operations");
        }
    }

    private void truncateBazaarAuthority() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        pending_commodity_deliveries,
                        bazaar_fills,
                        bazaar_orders,
                        economic_ledger,
                        processed_operations,
                        player_sessions,
                        player_state,
                        player_names,
                        bank_accounts,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }
}
