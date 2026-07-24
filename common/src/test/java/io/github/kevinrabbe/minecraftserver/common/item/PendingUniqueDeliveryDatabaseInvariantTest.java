package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import org.junit.jupiter.api.AfterAll;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PendingUniqueDeliveryDatabaseInvariantTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
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
    void genericItemMoveCannotDetachPendingCustodyFromDeliveryLifecycle() throws SQLException {
        UUID recipient = identities.ensurePlayer(UUID.randomUUID(), "PendingOwner");
        PendingUniqueDeliveryIssueResult issued = deliveries.issueNewIndividual(
                UUID.randomUUID(),
                "equipment.delivery_sword",
                recipient,
                "test.issue",
                recipient
        );

        assertThrows(
                SQLException.class,
                () -> itemAuthority.move(
                        UUID.randomUUID(),
                        issued.itemInstanceId(),
                        0,
                        ItemLocation.pendingDelivery(issued.deliveryId()),
                        ItemLocation.quarantine(),
                        "test.illegal_detach",
                        null
                )
        );

        assertEquals(PendingDeliveryStatus.PENDING, deliveries.load(issued.deliveryId()).status());
        assertEquals(
                ItemLocation.pendingDelivery(issued.deliveryId()),
                itemAuthority.load(issued.itemInstanceId()).location()
        );
        assertEquals(0, itemAuthority.load(issued.itemInstanceId()).stateVersion());
    }

    @Test
    void directSqlCannotClaimDeliveryWithoutMovingAuthoritativeItem() throws SQLException {
        UUID recipient = identities.ensurePlayer(UUID.randomUUID(), "DirectClaimOwner");
        PendingUniqueDeliveryIssueResult issued = deliveries.issueNewIndividual(
                UUID.randomUUID(),
                "equipment.delivery_sword",
                recipient,
                "test.issue",
                recipient
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE pending_unique_deliveries
                    SET status = 'CLAIMED',
                        claim_operation_id = ?,
                        claimed_at = NOW()
                    WHERE delivery_id = ?
                    """)) {
                update.setObject(1, UUID.randomUUID());
                update.setObject(2, issued.deliveryId());
                assertEquals(1, update.executeUpdate());
            }

            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }

        assertEquals(PendingDeliveryStatus.PENDING, deliveries.load(issued.deliveryId()).status());
        assertEquals(
                ItemLocation.pendingDelivery(issued.deliveryId()),
                itemAuthority.load(issued.itemInstanceId()).location()
        );
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }
}
