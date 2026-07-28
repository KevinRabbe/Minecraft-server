package io.github.kevinrabbe.minecraftserver.common.session;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceStatus;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class TransferInstancePinRepositoryIntegrationTest {
    private static final String SOURCE_BACKEND = "paper-source";
    private static final String TARGET_BACKEND_A = "paper-map-a";
    private static final String TARGET_BACKEND_B = "paper-map-b";
    private static final String MAP_ZONE = "map_encounter";
    private static final Duration SESSION_LEASE = Duration.ofMinutes(5);
    private static final Duration TICKET_LEASE = Duration.ofMinutes(1);
    private static final Duration ROUTE_FRESHNESS = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private BackendRegistry backends;
    private ZoneInstanceRegistry instances;
    private TransferInstancePinRepository pins;
    private TransferRoutingRepository routing;

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
        backends = new BackendRegistry(dataSource);
        instances = new ZoneInstanceRegistry(dataSource);
        pins = new TransferInstancePinRepository(dataSource, ROUTE_FRESHNESS);
        routing = new TransferRoutingRepository(dataSource, ROUTE_FRESHNESS);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        transfer_tickets,
                        player_sessions,
                        zone_instances,
                        backends,
                        player_names,
                        player_state,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
        backends.registerOnline(SOURCE_BACKEND, 1);
        backends.registerOnline(TARGET_BACKEND_A, 0);
        backends.registerOnline(TARGET_BACKEND_B, 0);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void pinnedTargetCannotSilentlyRerouteWhenReservedInstanceDies() throws Exception {
        UUID targetA = activeInstance(TARGET_BACKEND_A, MAP_ZONE, 5);
        UUID targetB = activeInstance(TARGET_BACKEND_B, MAP_ZONE, 0);
        TransferTicket ticket = openTransfer("PinExact");

        RoutedTransfer pinned = pins.pin(ticket.transferId(), TARGET_BACKEND_A, targetA);
        assertEquals(targetA, pinned.targetInstanceId());
        assertEquals(TARGET_BACKEND_A, pinned.targetBackendId());
        assertEquals(pinned, pins.pin(ticket.transferId(), TARGET_BACKEND_A, targetA));
        assertEquals(Optional.of(pinned), routing.route(ticket.transferId()));

        instances.markStopped(targetA);

        assertThrows(SQLException.class, () -> routing.route(ticket.transferId()));
        TransferTarget persisted = transferTarget(ticket.transferId());
        assertTrue(persisted.pinned());
        assertEquals(targetA, persisted.instanceId());
        assertEquals(TARGET_BACKEND_A, persisted.backendId());
        assertTrue(targetB != null); // A healthy alternative exists but must never replace the reserved target.
    }

    @Test
    void ordinaryUnpinnedTransferStillReroutesToAnotherHealthyInstance() throws Exception {
        UUID targetA = activeInstance(TARGET_BACKEND_A, MAP_ZONE, 5);
        UUID targetB = activeInstance(TARGET_BACKEND_B, MAP_ZONE, 0);
        TransferTicket ticket = openTransfer("RouteNormal");

        RoutedTransfer first = routing.route(ticket.transferId()).orElseThrow();
        assertEquals(targetA, first.targetInstanceId());
        assertEquals(TARGET_BACKEND_A, first.targetBackendId());

        instances.markStopped(targetA);
        RoutedTransfer replacement = routing.route(ticket.transferId()).orElseThrow();

        assertEquals(targetB, replacement.targetInstanceId());
        assertEquals(TARGET_BACKEND_B, replacement.targetBackendId());
        assertTrue(!transferTarget(ticket.transferId()).pinned());
    }

    @Test
    void pinRejectsWrongZoneAndCannotBeRetargeted() throws Exception {
        UUID targetA = activeInstance(TARGET_BACKEND_A, MAP_ZONE, 0);
        UUID wrongZone = activeInstance(TARGET_BACKEND_B, "starter_mine", 0);
        TransferTicket ticket = openTransfer("PinReject");

        assertThrows(
                SessionConflictException.class,
                () -> pins.pin(ticket.transferId(), TARGET_BACKEND_B, wrongZone)
        );

        pins.pin(ticket.transferId(), TARGET_BACKEND_A, targetA);
        assertThrows(
                SessionConflictException.class,
                () -> pins.pin(ticket.transferId(), TARGET_BACKEND_B, wrongZone)
        );
    }

    private TransferTicket openTransfer(String name) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, SOURCE_BACKEND, null, SESSION_LEASE);
        return sessions.beginTransfer(
                session.sessionId(),
                SOURCE_BACKEND,
                MAP_ZONE,
                session.stateVersion(),
                TICKET_LEASE
        );
    }

    private UUID activeInstance(String backendId, String zoneId, int playerCount) throws SQLException {
        UUID instanceId = UUID.randomUUID();
        instances.registerStarting(instanceId, zoneId, "v1", backendId, 10, 20);
        instances.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, playerCount);
        backends.heartbeat(backendId, playerCount);
        return instanceId;
    }

    private TransferTarget transferTarget(UUID transferId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT target_backend_id, target_instance_id, pinned_instance
                     FROM transfer_tickets
                     WHERE transfer_id = ?
                     """)) {
            statement.setObject(1, transferId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Unknown transfer: " + transferId);
                return new TransferTarget(
                        row.getString("target_backend_id"),
                        row.getObject("target_instance_id", UUID.class),
                        row.getBoolean("pinned_instance")
                );
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

    private record TransferTarget(String backendId, UUID instanceId, boolean pinned) { }
}
