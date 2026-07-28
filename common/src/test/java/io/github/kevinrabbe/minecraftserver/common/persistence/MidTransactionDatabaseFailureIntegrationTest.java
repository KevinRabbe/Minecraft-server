package io.github.kevinrabbe.minecraftserver.common.persistence;

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
import io.github.kevinrabbe.minecraftserver.common.verification.PersistentIntegrityVerifier;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that a database failure after partial in-transaction writes leaves no committed partial state. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MidTransactionDatabaseFailureIntegrationTest {
    private static final String ITEM_DEFINITION = "equipment.mid_transaction_failure_sword";
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
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
        itemCatalog = new ItemCatalog(List.of(new ItemDefinition(
                ITEM_DEFINITION,
                "IRON_SWORD",
                "Mid-Transaction Failure Sword",
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
                        player_attunement_state,
                        player_artifact_discoveries,
                        artifact_locations,
                        artifact_definitions,
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
    void failureAfterPlayerStateWritesRollsBackEntirePendingDeliveryClaim() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "MidTxnFailure");
        PendingUniqueDeliveryIssueResult issued = deliveries.issueNewIndividual(
                UUID.randomUUID(),
                ITEM_DEFINITION,
                playerId,
                "test.mid_transaction_issue",
                playerId
        );
        SessionLease lease = sessions.openSession(playerId, "paper-mid-transaction", null, LEASE);
        UUID claimOperationId = UUID.randomUUID();
        byte[] claimedPayload = new byte[]{9, 7, 5, 3, 1};

        PendingUniqueDeliveryRepository faulting = new PendingUniqueDeliveryRepository(
                failBeforeExecuteUpdateContaining(dataSource, "UPDATE item_instances"),
                itemCatalog
        );

        SQLException failure = assertThrows(
                SQLException.class,
                () -> faulting.claimToPlayerState(
                        claimOperationId,
                        issued.deliveryId(),
                        lease.sessionId(),
                        "paper-mid-transaction",
                        lease.stateVersion(),
                        "city",
                        "delivery-terminal",
                        claimedPayload,
                        "test.mid_transaction_claim"
                )
        );
        assertTrue(failure.getMessage().contains("simulated mid-transaction database failure"));

        assertEquals(PendingDeliveryStatus.PENDING, deliveries.load(issued.deliveryId()).status());
        assertNull(deliveries.load(issued.deliveryId()).claimOperationId());
        assertEquals(ItemLocation.pendingDelivery(issued.deliveryId()), itemAuthority.load(issued.itemInstanceId()).location());
        assertEquals(0L, itemAuthority.load(issued.itemInstanceId()).stateVersion());
        assertEquals(0L, states.load(playerId).stateVersion());
        assertNull(states.load(playerId).statePayload());
        assertEquals(0L, sessions.heartbeat(lease.sessionId(), "paper-mid-transaction", LEASE).stateVersion());
        assertEquals(0L, countByOperation("processed_operations", claimOperationId));
        assertEquals(0L, countByOperation("item_provenance", claimOperationId));

        PendingUniqueDeliveryClaimResult retry = deliveries.claimToPlayerState(
                claimOperationId,
                issued.deliveryId(),
                lease.sessionId(),
                "paper-mid-transaction",
                lease.stateVersion(),
                "city",
                "delivery-terminal",
                claimedPayload,
                "test.mid_transaction_claim"
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
        assertGlobalIntegrityClean();
    }

    private void assertGlobalIntegrityClean() throws SQLException {
        var issues = new PersistentIntegrityVerifier(dataSource, itemCatalog).verify(1_000);
        assertTrue(issues.isEmpty(), () -> "global integrity issues after adversarial recovery: " + issues);
    }

    private long countByOperation(String table, UUID operationId) throws SQLException {
        if (!List.of("processed_operations", "item_provenance").contains(table)) {
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

    private static DataSource failBeforeExecuteUpdateContaining(DataSource delegate, String sqlMarker) {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        return (DataSource) Proxy.newProxyInstance(
                MidTransactionDatabaseFailureIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if ("getConnection".equals(method.getName()) && result instanceof Connection connection) {
                        return wrapConnection(connection, sqlMarker, failOnce);
                    }
                    return result;
                }
        );
    }

    private static Connection wrapConnection(Connection delegate, String sqlMarker, AtomicBoolean failOnce) {
        return (Connection) Proxy.newProxyInstance(
                MidTransactionDatabaseFailureIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if ("prepareStatement".equals(method.getName())
                            && args != null
                            && args.length > 0
                            && args[0] instanceof String sql
                            && sql.contains(sqlMarker)
                            && result instanceof PreparedStatement statement) {
                        return wrapPreparedStatement(statement, failOnce);
                    }
                    return result;
                }
        );
    }

    private static PreparedStatement wrapPreparedStatement(PreparedStatement delegate, AtomicBoolean failOnce) {
        return (PreparedStatement) Proxy.newProxyInstance(
                MidTransactionDatabaseFailureIntegrationTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("executeUpdate".equals(method.getName())
                            && method.getParameterCount() == 0
                            && failOnce.compareAndSet(true, false)) {
                        throw new SQLException("simulated mid-transaction database failure before item custody update");
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
