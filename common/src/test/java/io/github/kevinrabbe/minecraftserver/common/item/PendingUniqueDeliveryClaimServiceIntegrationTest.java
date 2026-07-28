package io.github.kevinrabbe.minecraftserver.common.item;

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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PendingUniqueDeliveryClaimServiceIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String SWORD = "equipment.delivery_sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private PendingUniqueDeliveryRepository deliveries;
    private UniqueItemAuthorityRepository items;

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
                SWORD,
                "IRON_SWORD",
                "Delivery Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        deliveries = new PendingUniqueDeliveryRepository(dataSource, catalog);
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
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
        if (database != null) database.close();
    }

    @Test
    void projectsExactPostClaimItemAndSameOperationRetryReturnsSameCommit() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "ProjectedItem");
        PendingUniqueDeliveryIssueResult issued = deliveries.issueNewIndividual(
                UUID.randomUUID(), SWORD, playerId, "test.issue", playerId
        );
        SessionLease lease = sessions.openSession(playerId, "paper-a", null, LEASE);
        byte[] currentPayload = new byte[]{3, 2, 1};
        byte[] nextPayload = new byte[]{8, 6, 7, 5};
        AtomicReference<UniqueItemInstance> observedProjection = new AtomicReference<>();
        PendingUniqueDeliveryClaimService service = new PendingUniqueDeliveryClaimService(
                deliveries,
                items,
                (resolvedPlayerId, projectedItem, payload) -> {
                    assertEquals(playerId, resolvedPlayerId);
                    assertArrayEquals(currentPayload, payload);
                    assertEquals(ItemLocation.playerInventory(playerId), projectedItem.location());
                    assertEquals(1L, projectedItem.stateVersion());
                    observedProjection.set(projectedItem);
                    return Arrays.copyOf(nextPayload, nextPayload.length);
                }
        );
        UUID operationId = UUID.randomUUID();

        PendingUniqueDeliveryMaterializationResult first = service.claim(
                operationId,
                issued.deliveryId(),
                lease.sessionId(),
                "paper-a",
                lease.stateVersion(),
                "city",
                "delivery-terminal",
                currentPayload,
                "test.delivery_claim"
        );
        PendingUniqueDeliveryMaterializationResult retry = service.claim(
                operationId,
                issued.deliveryId(),
                lease.sessionId(),
                "paper-a",
                lease.stateVersion(),
                "city",
                "delivery-terminal",
                currentPayload,
                "test.delivery_claim"
        );

        assertEquals(first.claim(), retry.claim());
        assertArrayEquals(first.statePayload(), retry.statePayload());
        assertEquals(1L, first.claim().itemStateVersion());
        assertEquals(1L, first.claim().playerStateVersion());
        assertEquals(ItemLocation.playerInventory(playerId), items.load(issued.itemInstanceId()).location());
        assertEquals(1L, items.load(issued.itemInstanceId()).stateVersion());
        assertArrayEquals(nextPayload, states.load(playerId).statePayload());
        assertEquals(1L, states.load(playerId).stateVersion());
        assertEquals(1L, observedProjection.get().stateVersion());
    }

    @Test
    void adapterMutationFailureLeavesAllAuthorityPendingAndUnchanged() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "FullDelivery");
        PendingUniqueDeliveryIssueResult issued = deliveries.issueNewIndividual(
                UUID.randomUUID(), SWORD, playerId, "test.issue", playerId
        );
        SessionLease lease = sessions.openSession(playerId, "paper-a", null, LEASE);
        PendingUniqueDeliveryClaimService service = new PendingUniqueDeliveryClaimService(
                deliveries,
                items,
                (resolvedPlayerId, projectedItem, payload) -> {
                    throw new PendingUniqueDeliveryException("Authoritative player inventory has insufficient space");
                }
        );

        assertThrows(
                PendingUniqueDeliveryException.class,
                () -> service.claim(
                        UUID.randomUUID(),
                        issued.deliveryId(),
                        lease.sessionId(),
                        "paper-a",
                        lease.stateVersion(),
                        "city",
                        null,
                        new byte[]{1},
                        "test.delivery_claim"
                )
        );

        assertEquals(PendingDeliveryStatus.PENDING, deliveries.load(issued.deliveryId()).status());
        assertEquals(ItemLocation.pendingDelivery(issued.deliveryId()), items.load(issued.itemInstanceId()).location());
        assertEquals(0L, items.load(issued.itemInstanceId()).stateVersion());
        assertEquals(0L, states.load(playerId).stateVersion());
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }
}
