package io.github.kevinrabbe.minecraftserver.common.persistence;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletMutationResult;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemLocation;
import io.github.kevinrabbe.minecraftserver.common.item.PendingDeliveryStatus;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryClaimResult;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryIssueResult;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves idempotent recovery when PostgreSQL commits but the caller loses the commit acknowledgement. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PostCommitResponseLossIntegrationTest {
    private static final String ITEM_DEFINITION = "equipment.ambiguous_commit_sword";
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private CoinWalletRepository wallets;
    private ItemCatalog itemCatalog;
    private UniqueItemAuthorityRepository itemAuthority;
    private PendingUniqueDeliveryRepository deliveries;

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
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);
        wallets = new CoinWalletRepository(dataSource);
        itemCatalog = new ItemCatalog(List.of(new ItemDefinition(
                ITEM_DEFINITION,
                "IRON_SWORD",
                "Ambiguous Commit Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        itemAuthority = new UniqueItemAuthorityRepository(dataSource, itemCatalog);
        deliveries = new PendingUniqueDeliveryRepository(dataSource, itemCatalog);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        pending_unique_deliveries,
                        item_provenance,
                        item_instances,
                        wallets,
                        transfer_tickets,
                        economic_ledger,
                        processed_operations,
                        player_sessions,
                        zone_instances,
                        backends,
                        player_state,
                        player_names,
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
    void committedCoinCreditSurvivesLostCommitAcknowledgementAndRetryDoesNotCreditTwice() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "LostCommitCoin");
        UUID operationId = UUID.randomUUID();
        DataSource ambiguousCommit = failAfterNextSuccessfulCommit(dataSource);
        CoinWalletRepository firstAttempt = new CoinWalletRepository(ambiguousCommit);

        SQLException lostResponse = assertThrows(
                SQLException.class,
                () -> firstAttempt.creditFromSystem(
                        operationId,
                        playerId,
                        500L,
                        "test.ambiguous_commit_coin"
                )
        );
        assertTrue(lostResponse.getMessage().contains("simulated lost commit acknowledgement"));

        assertEquals(500L, wallets.load(playerId).balanceMinor());
        assertEquals(1L, wallets.load(playerId).stateVersion());
        assertEquals(1L, countByOperation("processed_operations", operationId));
        assertEquals(1L, countByOperation("economic_ledger", operationId));

        CoinWalletMutationResult retry = wallets.creditFromSystem(
                operationId,
                playerId,
                500L,
                "test.ambiguous_commit_coin"
        );

        assertEquals(playerId, retry.playerId());
        assertEquals(500L, retry.amountMinor());
        assertEquals(500L, retry.balanceMinor());
        assertEquals(1L, retry.stateVersion());
        assertEquals(500L, wallets.load(playerId).balanceMinor());
        assertEquals(1L, wallets.load(playerId).stateVersion());
        assertEquals(1L, countByOperation("processed_operations", operationId));
        assertEquals(1L, countByOperation("economic_ledger", operationId));
    }

    @Test
    void committedItemClaimSurvivesLostCommitAcknowledgementAndRetryReturnsFrozenResult() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "LostCommitItem");
        PendingUniqueDeliveryIssueResult issued = deliveries.issueNewIndividual(
                UUID.randomUUID(),
                ITEM_DEFINITION,
                playerId,
                "test.ambiguous_commit_issue",
                playerId
        );
        SessionLease lease = sessions.openSession(playerId, "paper-ambiguous", null, LEASE);
        UUID claimOperationId = UUID.randomUUID();
        byte[] claimedPayload = new byte[]{7, 5, 3, 1};

        PendingUniqueDeliveryRepository firstAttempt = new PendingUniqueDeliveryRepository(
                failAfterNextSuccessfulCommit(dataSource),
                itemCatalog
        );
        SQLException lostResponse = assertThrows(
                SQLException.class,
                () -> firstAttempt.claimToPlayerState(
                        claimOperationId,
                        issued.deliveryId(),
                        lease.sessionId(),
                        "paper-ambiguous",
                        lease.stateVersion(),
                        "city",
                        "delivery-terminal",
                        claimedPayload,
                        "test.ambiguous_commit_claim"
                )
        );
        assertTrue(lostResponse.getMessage().contains("simulated lost commit acknowledgement"));

        assertEquals(PendingDeliveryStatus.CLAIMED, deliveries.load(issued.deliveryId()).status());
        assertEquals(ItemLocation.playerInventory(playerId), itemAuthority.load(issued.itemInstanceId()).location());
        assertEquals(1L, itemAuthority.load(issued.itemInstanceId()).stateVersion());
        assertEquals(1L, states.load(playerId).stateVersion());
        assertArrayEquals(claimedPayload, states.load(playerId).statePayload());
        assertEquals(1L, countByOperation("processed_operations", claimOperationId));
        assertEquals(1L, countByOperation("item_provenance", claimOperationId));

        PendingUniqueDeliveryClaimResult retry = deliveries.claimToPlayerState(
                claimOperationId,
                issued.deliveryId(),
                lease.sessionId(),
                "paper-ambiguous",
                lease.stateVersion(),
                "city",
                "delivery-terminal",
                claimedPayload,
                "test.ambiguous_commit_claim"
        );

        assertEquals(issued.deliveryId(), retry.deliveryId());
        assertEquals(issued.itemInstanceId(), retry.itemInstanceId());
        assertEquals(1L, retry.itemStateVersion());
        assertEquals(1L, retry.playerStateVersion());
        assertEquals(PendingDeliveryStatus.CLAIMED, deliveries.load(issued.deliveryId()).status());
        assertEquals(ItemLocation.playerInventory(playerId), itemAuthority.load(issued.itemInstanceId()).location());
        assertEquals(1L, itemAuthority.load(issued.itemInstanceId()).stateVersion());
        assertEquals(1L, states.load(playerId).stateVersion());
        assertArrayEquals(claimedPayload, states.load(playerId).statePayload());
        assertEquals(1L, countByOperation("processed_operations", claimOperationId));
        assertEquals(1L, countByOperation("item_provenance", claimOperationId));
        assertEquals(1L, count("SELECT COUNT(*) FROM pending_unique_deliveries"));
        assertEquals(1L, count("SELECT COUNT(*) FROM item_instances"));
        assertEquals(2L, count("SELECT COUNT(*) FROM item_provenance"));
    }

    private long countByOperation(String table, UUID operationId) throws SQLException {
        if (!List.of("processed_operations", "economic_ledger", "item_provenance").contains(table)) {
            throw new IllegalArgumentException("unexpected table: " + table);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE operation_id = ?"
             )) {
            statement.setObject(1, operationId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static DataSource failAfterNextSuccessfulCommit(DataSource delegate) {
        AtomicBoolean failNextCommit = new AtomicBoolean(true);
        return (DataSource) Proxy.newProxyInstance(
                PostCommitResponseLossIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if ("getConnection".equals(method.getName()) && result instanceof Connection connection) {
                        return wrapConnection(connection, failNextCommit);
                    }
                    return result;
                }
        );
    }

    private static Connection wrapConnection(Connection delegate, AtomicBoolean failNextCommit) {
        return (Connection) Proxy.newProxyInstance(
                PostCommitResponseLossIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("commit".equals(method.getName())
                            && method.getParameterCount() == 0
                            && failNextCommit.compareAndSet(true, false)) {
                        invoke(delegate, method, args);
                        throw new SQLException("simulated lost commit acknowledgement after successful database commit");
                    }
                    return invoke(delegate, method, args);
                }
        );
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
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
