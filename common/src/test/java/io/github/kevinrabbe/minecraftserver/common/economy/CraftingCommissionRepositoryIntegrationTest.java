package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CraftingCommissionRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String IRON = "craft.iron";
    private static final String COAL = "craft.coal";
    private static final String UNIQUE = "craft.tool";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private CoinWalletRepository wallets;
    private ItemCatalog catalog;

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
        catalog = new ItemCatalog(List.of(
                commodity(IRON, "IRON_INGOT"),
                commodity(COAL, "COAL"),
                new ItemDefinition(
                        UNIQUE,
                        "IRON_PICKAXE",
                        "Craft Tool",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                )
        ));
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        crafting_commission_returns,
                        crafting_commission_materials,
                        crafting_commissions,
                        pending_commodity_deliveries,
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
    void fundedCreationEscrowsPaymentAndExactMaterialBatchOnce() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CommissionRequester", 10_000, new byte[]{9, 4});
        AtomicInteger validations = new AtomicInteger();
        CraftingCommissionRepository commissions = repository(
                (playerId, materials, currentPayload, nextPayload) -> {
                    validations.incrementAndGet();
                    assertEquals(requester.playerId(), playerId);
                    assertEquals(Map.of(COAL, 2L, IRON, 5L), materials);
                    assertArrayEquals(new byte[]{9, 4}, currentPayload);
                    assertArrayEquals(new byte[]{4, 2}, nextPayload);
                }
        );
        CraftingCommissionRequest request = request(2_000);
        UUID operationId = UUID.randomUUID();

        CraftingCommissionCreateResult first = commissions.createFunded(
                operationId,
                requester.session().sessionId(),
                "paper-a",
                requester.session().stateVersion(),
                request,
                "city",
                "forge",
                new byte[]{4, 2},
                "commission.create"
        );
        CraftingCommissionCreateResult retry = commissions.createFunded(
                operationId,
                requester.session().sessionId(),
                "paper-a",
                requester.session().stateVersion(),
                request,
                "city",
                "forge",
                new byte[]{4, 2},
                "commission.create"
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertEquals(CraftingCommissionStatus.OPEN, first.commission().status());
        assertEquals(8_000L, first.walletBalanceMinor());
        assertEquals(8_000L, wallets.load(requester.playerId()).balanceMinor());
        assertEquals(Map.of(COAL, 2L, IRON, 5L), first.commission().materialQuantities());
        assertArrayEquals(new byte[]{4, 2}, states.load(requester.playerId()).statePayload());
        assertEquals(1L, processedCount(operationId));
    }

    @Test
    void validatorFailureRollsBackMoneyPlayerStateAndCommissionRows() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CommissionRollback", 10_000, new byte[]{9});
        CraftingCommissionRepository commissions = repository(
                (playerId, materials, currentPayload, nextPayload) -> {
                    throw new CraftingCommissionException("bad inventory delta");
                }
        );

        assertThrows(
                CraftingCommissionException.class,
                () -> commissions.createFunded(
                        UUID.randomUUID(),
                        requester.session().sessionId(),
                        "paper-a",
                        requester.session().stateVersion(),
                        request(2_000),
                        "city",
                        "forge",
                        new byte[]{1},
                        "commission.create"
                )
        );

        assertEquals(10_000L, wallets.load(requester.playerId()).balanceMinor());
        assertEquals(requester.session().stateVersion(), states.load(requester.playerId()).stateVersion());
        assertArrayEquals(new byte[]{9}, states.load(requester.playerId()).statePayload());
        assertEquals(0L, tableCount("crafting_commissions"));
        assertEquals(0L, tableCount("crafting_commission_materials"));
    }

    @Test
    void nonCommodityMaterialIsRejectedBeforeAnyValueMoves() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CommissionBadMaterial", 10_000, new byte[]{9});
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> { });
        CraftingCommissionRequest bad = new CraftingCommissionRequest(
                "tool.recipe",
                1,
                Map.of(UNIQUE, 1L),
                1_000
        );

        assertThrows(
                CraftingCommissionException.class,
                () -> commissions.createFunded(
                        UUID.randomUUID(),
                        requester.session().sessionId(),
                        "paper-a",
                        requester.session().stateVersion(),
                        bad,
                        "city",
                        "forge",
                        new byte[0],
                        "commission.create"
                )
        );
        assertEquals(10_000L, wallets.load(requester.playerId()).balanceMinor());
        assertEquals(0L, tableCount("crafting_commissions"));
    }

    @Test
    void oneWorkerWinsConcurrentAcceptanceAndRequesterCannotSelfAccept() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CommissionAcceptRequester", 10_000, new byte[]{9});
        UUID workerA = identities.ensurePlayer(UUID.randomUUID(), "CommissionWorkerA");
        UUID workerB = identities.ensurePlayer(UUID.randomUUID(), "CommissionWorkerB");
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> { });
        CraftingCommissionSnapshot open = commissions.createFunded(
                UUID.randomUUID(),
                requester.session().sessionId(),
                "paper-a",
                requester.session().stateVersion(),
                request(1_000),
                "city",
                "forge",
                new byte[]{1},
                "commission.create"
        ).commission();

        assertThrows(
                CraftingCommissionException.class,
                () -> commissions.accept(UUID.randomUUID(), open.commissionId(), requester.playerId())
        );

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CraftingCommissionSnapshot> first = executor.submit(
                    () -> commissions.accept(UUID.randomUUID(), open.commissionId(), workerA)
            );
            Future<CraftingCommissionSnapshot> second = executor.submit(
                    () -> commissions.accept(UUID.randomUUID(), open.commissionId(), workerB)
            );
            for (Future<CraftingCommissionSnapshot> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof CraftingCommissionException);
                }
            }
        }

        assertEquals(1, successes);
        CraftingCommissionSnapshot accepted = commissions.load(open.commissionId());
        assertEquals(CraftingCommissionStatus.ACCEPTED, accepted.status());
        assertTrue(accepted.workerPlayerId().equals(workerA) || accepted.workerPlayerId().equals(workerB));
        assertTrue(accepted.stateVersion() > open.stateVersion());
    }

    @Test
    void cancellationReturnsPaymentAndEveryMaterialThroughDurableDeliveryExactlyOnce() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CommissionCancel", 10_000, new byte[]{9});
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> { });
        CraftingCommissionSnapshot open = commissions.createFunded(
                UUID.randomUUID(),
                requester.session().sessionId(),
                "paper-a",
                requester.session().stateVersion(),
                request(2_000),
                "city",
                "forge",
                new byte[]{1},
                "commission.create"
        ).commission();
        UUID operationId = UUID.randomUUID();

        CraftingCommissionCancelResult first = commissions.cancelOpen(
                operationId,
                open.commissionId(),
                requester.playerId(),
                "commission.cancel"
        );
        CraftingCommissionCancelResult retry = commissions.cancelOpen(
                operationId,
                open.commissionId(),
                requester.playerId(),
                "commission.cancel"
        );

        assertEquals(first, retry);
        assertEquals(CraftingCommissionStatus.CANCELLED, first.commission().status());
        assertEquals(10_000L, first.walletBalanceMinor());
        assertEquals(10_000L, wallets.load(requester.playerId()).balanceMinor());
        assertEquals(2, first.materialReturns().size());
        assertEquals(2L, tableCount("pending_commodity_deliveries"));
        assertEquals(2L, tableCount("crafting_commission_returns"));
        for (CraftingCommissionReturn materialReturn : first.materialReturns()) {
            assertPendingReturn(materialReturn, requester.playerId());
        }
        assertEquals(1L, processedCount(operationId));
    }

    @Test
    void acceptedCommissionCannotCancelAndDatabaseRejectsBackwardStateTransition() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CommissionNoCancel", 10_000, new byte[]{9});
        UUID worker = identities.ensurePlayer(UUID.randomUUID(), "CommissionNoCancelWorker");
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> { });
        CraftingCommissionSnapshot open = commissions.createFunded(
                UUID.randomUUID(),
                requester.session().sessionId(),
                "paper-a",
                requester.session().stateVersion(),
                request(1_000),
                "city",
                "forge",
                new byte[]{1},
                "commission.create"
        ).commission();
        CraftingCommissionSnapshot accepted = commissions.accept(UUID.randomUUID(), open.commissionId(), worker);

        assertThrows(
                CraftingCommissionException.class,
                () -> commissions.cancelOpen(
                        UUID.randomUUID(),
                        accepted.commissionId(),
                        requester.playerId(),
                        "commission.cancel"
                )
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE crafting_commissions
                     SET status = 'OPEN',
                         worker_player_id = NULL,
                         accept_operation_id = NULL,
                         accepted_at = NULL
                     WHERE commission_id = ?
                     """)) {
            statement.setObject(1, accepted.commissionId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
        assertEquals(CraftingCommissionStatus.ACCEPTED, commissions.load(accepted.commissionId()).status());
    }

    @Test
    void sameCreateOperationCannotBeReboundToDifferentPayloadOrPayment() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CommissionBinding", 10_000, new byte[]{9});
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> { });
        UUID operationId = UUID.randomUUID();
        commissions.createFunded(
                operationId,
                requester.session().sessionId(),
                "paper-a",
                requester.session().stateVersion(),
                request(1_000),
                "city",
                "forge",
                new byte[]{1},
                "commission.create"
        );

        assertThrows(
                CraftingCommissionException.class,
                () -> commissions.createFunded(
                        operationId,
                        requester.session().sessionId(),
                        "paper-a",
                        requester.session().stateVersion(),
                        request(2_000),
                        "city",
                        "forge",
                        new byte[]{1},
                        "commission.create"
                )
        );
        assertThrows(
                CraftingCommissionException.class,
                () -> commissions.createFunded(
                        operationId,
                        requester.session().sessionId(),
                        "paper-a",
                        requester.session().stateVersion(),
                        request(1_000),
                        "city",
                        "forge",
                        new byte[]{2},
                        "commission.create"
                )
        );
    }

    private CraftingCommissionRepository repository(CommodityBatchEscrowValidator validator) {
        return new CraftingCommissionRepository(dataSource, catalog, validator);
    }

    private CraftingCommissionRequest request(long paymentMinor) {
        return new CraftingCommissionRequest(
                "tool.recipe",
                1,
                Map.of(IRON, 5L, COAL, 2L),
                paymentMinor
        );
    }

    private PlayerContext fundedPlayerWithSession(String name, long amount, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, amount, "test.funding");
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long stateVersion = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "forge", payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(stateVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void assertPendingReturn(CraftingCommissionReturn materialReturn, UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id, commodity_definition_id, quantity, status
                     FROM pending_commodity_deliveries
                     WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, materialReturn.deliveryId());
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(playerId, row.getObject("player_id", UUID.class));
                assertEquals(materialReturn.commodityDefinitionId(), row.getString("commodity_definition_id"));
                assertEquals(materialReturn.quantity(), row.getLong("quantity"));
                assertEquals("PENDING", row.getString("status"));
            }
        }
    }

    private long tableCount(String table) throws SQLException {
        if (!List.of(
                "crafting_commissions",
                "crafting_commission_materials",
                "pending_commodity_deliveries",
                "crafting_commission_returns"
        ).contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
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

    private static ItemDefinition commodity(String id, String material) {
        return new ItemDefinition(
                id,
                material,
                id,
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        );
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
