package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateSnapshot;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PendingUniqueDeliveryRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
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

        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                "equipment.delivery_sword",
                "IRON_SWORD",
                "Delivery Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        itemAuthority = new UniqueItemAuthorityRepository(dataSource, catalog);
        deliveries = new PendingUniqueDeliveryRepository(dataSource, catalog);
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
    void issueCreatesOneOwnedItemInPendingCustodyAndIsIdempotent() throws SQLException {
        UUID recipient = createPlayer("DeliveryRecipient");
        UUID operationId = UUID.randomUUID();

        PendingUniqueDeliveryIssueResult first = deliveries.issueNewIndividual(
                operationId,
                "equipment.delivery_sword",
                recipient,
                "test.delivery_issue",
                recipient
        );
        PendingUniqueDeliveryIssueResult retry = deliveries.issueNewIndividual(
                operationId,
                "equipment.delivery_sword",
                recipient,
                "test.delivery_issue",
                recipient
        );

        assertEquals(first, retry);
        PendingUniqueDelivery delivery = deliveries.load(first.deliveryId());
        assertEquals(PendingDeliveryStatus.PENDING, delivery.status());
        assertEquals(recipient, delivery.recipientPlayerId());
        assertEquals(first.itemInstanceId(), delivery.itemInstanceId());
        assertNull(delivery.claimOperationId());

        UniqueItemInstance item = itemAuthority.load(first.itemInstanceId());
        assertEquals(ItemLocation.pendingDelivery(first.deliveryId()), item.location());
        assertEquals(0, item.stateVersion());
        assertEquals(recipient, item.originalOwnerPlayerId());

        assertEquals(1, count("SELECT COUNT(*) FROM pending_unique_deliveries"));
        assertEquals(1, count("SELECT COUNT(*) FROM item_instances"));
        assertEquals(1, count("SELECT COUNT(*) FROM item_provenance"));
        assertEquals(1, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(1, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void claimAtomicallyUpdatesPlayerSnapshotItemCustodyAndDeliveryLifecycle() throws SQLException {
        UUID recipient = createPlayer("ClaimRecipient");
        PendingUniqueDeliveryIssueResult issued = issue(recipient);
        SessionLease lease = sessions.openSession(recipient, "paper-a", null, LEASE);
        byte[] claimedPayload = new byte[]{5, 4, 3, 2, 1};
        UUID claimOperation = UUID.randomUUID();

        PendingUniqueDeliveryClaimResult first = deliveries.claimToPlayerState(
                claimOperation,
                issued.deliveryId(),
                lease.sessionId(),
                "paper-a",
                lease.stateVersion(),
                "city",
                "delivery-terminal",
                claimedPayload,
                "test.delivery_claim"
        );
        PendingUniqueDeliveryClaimResult retry = deliveries.claimToPlayerState(
                claimOperation,
                issued.deliveryId(),
                lease.sessionId(),
                "paper-a",
                lease.stateVersion(),
                "city",
                "delivery-terminal",
                claimedPayload,
                "test.delivery_claim"
        );

        assertEquals(first, retry);
        assertEquals(1, first.itemStateVersion());
        assertEquals(1, first.playerStateVersion());

        PendingUniqueDelivery delivery = deliveries.load(issued.deliveryId());
        assertEquals(PendingDeliveryStatus.CLAIMED, delivery.status());
        assertEquals(claimOperation, delivery.claimOperationId());

        UniqueItemInstance item = itemAuthority.load(issued.itemInstanceId());
        assertEquals(ItemLocation.playerInventory(recipient), item.location());
        assertEquals(1, item.stateVersion());

        PlayerStateSnapshot state = states.load(recipient);
        assertEquals(1, state.stateVersion());
        assertEquals("city", state.logicalZoneId());
        assertEquals("delivery-terminal", state.entryPoint());
        assertArrayEquals(claimedPayload, state.statePayload());

        assertEquals(2, count("SELECT COUNT(*) FROM item_provenance"));
        assertEquals(1, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(2, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void stalePlayerStateVersionRollsBackEntireClaim() throws SQLException {
        UUID recipient = createPlayer("StaleRecipient");
        PendingUniqueDeliveryIssueResult issued = issue(recipient);
        SessionLease lease = sessions.openSession(recipient, "paper-a", null, LEASE);

        states.commit(
                lease.sessionId(),
                "paper-a",
                0,
                "city",
                "spawn",
                new byte[]{9}
        );

        assertThrows(
                SessionConflictException.class,
                () -> deliveries.claimToPlayerState(
                        UUID.randomUUID(),
                        issued.deliveryId(),
                        lease.sessionId(),
                        "paper-a",
                        0,
                        "city",
                        "delivery-terminal",
                        new byte[]{1, 2, 3},
                        "test.stale_claim"
                )
        );

        assertEquals(PendingDeliveryStatus.PENDING, deliveries.load(issued.deliveryId()).status());
        assertEquals(
                ItemLocation.pendingDelivery(issued.deliveryId()),
                itemAuthority.load(issued.itemInstanceId()).location()
        );
        assertEquals(0, itemAuthority.load(issued.itemInstanceId()).stateVersion());
        assertEquals(1, states.load(recipient).stateVersion());
        assertArrayEquals(new byte[]{9}, states.load(recipient).statePayload());
        assertEquals(1, count("SELECT COUNT(*) FROM item_provenance"));
        assertEquals(1, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(2, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void anotherPlayersSessionCannotClaimDelivery() throws SQLException {
        UUID recipient = createPlayer("RealRecipient");
        UUID otherPlayer = createPlayer("WrongRecipient");
        PendingUniqueDeliveryIssueResult issued = issue(recipient);
        SessionLease wrongLease = sessions.openSession(otherPlayer, "paper-a", null, LEASE);

        assertThrows(
                SessionConflictException.class,
                () -> deliveries.claimToPlayerState(
                        UUID.randomUUID(),
                        issued.deliveryId(),
                        wrongLease.sessionId(),
                        "paper-a",
                        wrongLease.stateVersion(),
                        "city",
                        null,
                        new byte[]{7},
                        "test.wrong_recipient"
                )
        );

        assertEquals(PendingDeliveryStatus.PENDING, deliveries.load(issued.deliveryId()).status());
        assertEquals(
                ItemLocation.pendingDelivery(issued.deliveryId()),
                itemAuthority.load(issued.itemInstanceId()).location()
        );
        assertEquals(0, states.load(otherPlayer).stateVersion());
    }

    @Test
    void claimOperationIdIsBoundToPayloadAndRequest() throws SQLException {
        UUID recipient = createPlayer("BoundClaim");
        PendingUniqueDeliveryIssueResult issued = issue(recipient);
        SessionLease lease = sessions.openSession(recipient, "paper-a", null, LEASE);
        UUID operationId = UUID.randomUUID();

        deliveries.claimToPlayerState(
                operationId,
                issued.deliveryId(),
                lease.sessionId(),
                "paper-a",
                0,
                "city",
                null,
                new byte[]{1, 2, 3},
                "test.claim"
        );

        assertThrows(
                PendingUniqueDeliveryException.class,
                () -> deliveries.claimToPlayerState(
                        operationId,
                        issued.deliveryId(),
                        lease.sessionId(),
                        "paper-a",
                        0,
                        "city",
                        null,
                        new byte[]{1, 2, 4},
                        "test.claim"
                )
        );
        assertThrows(
                PendingUniqueDeliveryException.class,
                () -> deliveries.claimToPlayerState(
                        operationId,
                        issued.deliveryId(),
                        lease.sessionId(),
                        "paper-a",
                        0,
                        "city",
                        "changed-entry",
                        new byte[]{1, 2, 3},
                        "test.claim"
                )
        );
    }

    private PendingUniqueDeliveryIssueResult issue(UUID recipient) throws SQLException {
        return deliveries.issueNewIndividual(
                UUID.randomUUID(),
                "equipment.delivery_sword",
                recipient,
                "test.issue",
                recipient
        );
    }

    private UUID createPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            results.next();
            return results.getLong(1);
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
