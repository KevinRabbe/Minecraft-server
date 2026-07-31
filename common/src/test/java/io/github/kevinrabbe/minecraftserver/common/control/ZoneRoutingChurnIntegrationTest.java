package io.github.kevinrabbe.minecraftserver.common.control;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the settled routing eligibility rules through capacity, heartbeat, and lifecycle churn. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ZoneRoutingChurnIntegrationTest {
    private static final Duration HEARTBEAT_FRESHNESS = Duration.ofSeconds(30);
    private static final String ZONE = "churn-zone";

    private Database database;
    private DataSource dataSource;
    private BackendRegistry backends;
    private ZoneInstanceRegistry instances;
    private ZoneRouter router;

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
        backends = new BackendRegistry(dataSource);
        instances = new ZoneInstanceRegistry(dataSource);
        router = new ZoneRouter(dataSource, HEARTBEAT_FRESHNESS);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        transfer_tickets,
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
    void routingAlwaysMovesToAnEligibleTargetAsCapacityAndHealthChurn() throws Exception {
        backends.registerStarting("paper-a");
        backends.registerOnline("paper-b", 4);

        UUID instanceA = registerStarting("paper-a", 10, 12);
        UUID instanceB = registerStarting("paper-b", 10, 12);

        // STARTING instances are never routable.
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty());

        instances.heartbeat(instanceA, ZoneInstanceStatus.ACTIVE, 8);
        instances.heartbeat(instanceB, ZoneInstanceStatus.ACTIVE, 4);
        assertRoute(instanceB, "paper-b"); // an ACTIVE instance remains hidden while its backend is STARTING.

        assertThrows(SQLException.class, () -> backends.heartbeat("paper-a", 8));
        assertRoute(instanceB, "paper-b"); // ordinary heartbeat cannot accidentally publish startup.

        backends.publishOnline("paper-a", 8);
        assertRoute(instanceA, "paper-a"); // explicit publication makes the denser eligible instance routable.

        instances.heartbeat(instanceA, ZoneInstanceStatus.ACTIVE, 12);
        assertRoute(instanceB, "paper-b"); // hard-cap excludes A entirely.

        instances.heartbeat(instanceA, ZoneInstanceStatus.ACTIVE, 8);
        staleInstanceHeartbeat(instanceB);
        assertRoute(instanceA, "paper-a"); // stale instance heartbeat excludes B.

        instances.heartbeat(instanceA, ZoneInstanceStatus.DRAINING, 8);
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty());

        instances.heartbeat(instanceB, ZoneInstanceStatus.ACTIVE, 5);
        assertRoute(instanceB, "paper-b");

        staleBackendHeartbeat("paper-b");
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty()); // live instance cannot outlive backend health.

        backends.heartbeat("paper-b", 5);
        assertRoute(instanceB, "paper-b");

        backends.markDraining("paper-b");
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty());

        backends.heartbeat("paper-b", 5);
        assertRoute(instanceB, "paper-b"); // a fresh heartbeat explicitly returns the backend ONLINE.

        backends.markOffline("paper-b");
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty());
        assertThrows(SQLException.class, () -> backends.heartbeat("paper-b", 5));
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty()); // late heartbeat cannot revive shutdown.

        backends.registerOnline("paper-b", 5);
        assertThrows(
                SQLException.class,
                () -> instances.heartbeat(instanceB, ZoneInstanceStatus.ACTIVE, 5)
        );
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty()); // old zone belongs to the previous incarnation.

        UUID replacementInstanceB = registerStarting("paper-b", 10, 12);
        instances.heartbeat(replacementInstanceB, ZoneInstanceStatus.ACTIVE, 5);
        assertRoute(replacementInstanceB, "paper-b");

        instances.markStopped(replacementInstanceB);
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty());
    }

    @Test
    void replacementIncarnationFencesOlderBackendAndZoneProcesses() throws Exception {
        BackendRegistry oldProcess = new BackendRegistry(dataSource);
        ZoneInstanceRegistry oldZoneProcess = new ZoneInstanceRegistry(dataSource);
        UUID oldIncarnation = oldProcess.registerOnline("paper-reused", 3);
        UUID oldInstanceId = UUID.randomUUID();
        oldZoneProcess.registerStarting(
                oldInstanceId, ZONE, "v1", "paper-reused", oldIncarnation, 10, 12
        );
        oldZoneProcess.heartbeat(oldInstanceId, ZoneInstanceStatus.ACTIVE, 3);
        assertRoute(oldInstanceId, "paper-reused");

        BackendRegistry replacementProcess = new BackendRegistry(dataSource);
        ZoneInstanceRegistry replacementZoneProcess = new ZoneInstanceRegistry(dataSource);
        UUID replacementIncarnation = replacementProcess.registerStarting("paper-reused");
        assertNotEquals(oldIncarnation, replacementIncarnation);
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty());

        assertThrows(
                SQLException.class,
                () -> oldZoneProcess.heartbeat(oldInstanceId, ZoneInstanceStatus.ACTIVE, 3)
        );
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty());
        assertThrows(SQLException.class, () -> oldProcess.heartbeat("paper-reused", 3));
        assertThrows(SQLException.class, () -> oldProcess.markOffline("paper-reused"));

        UUID replacementInstanceId = UUID.randomUUID();
        replacementZoneProcess.registerStarting(
                replacementInstanceId, ZONE, "v1", "paper-reused", replacementIncarnation, 10, 12
        );
        replacementZoneProcess.heartbeat(replacementInstanceId, ZoneInstanceStatus.ACTIVE, 4);
        assertTrue(router.findPreferredActiveInstance(ZONE).isEmpty());

        replacementProcess.publishOnline("paper-reused", 4);
        assertRoute(replacementInstanceId, "paper-reused");

        assertThrows(
                SQLException.class,
                () -> oldZoneProcess.heartbeat(oldInstanceId, ZoneInstanceStatus.ACTIVE, 9)
        );
        assertThrows(SQLException.class, () -> oldProcess.heartbeat("paper-reused", 3));
        assertThrows(SQLException.class, () -> oldProcess.markOffline("paper-reused"));
        assertRoute(replacementInstanceId, "paper-reused");

        replacementProcess.heartbeat("paper-reused", 4);
        replacementZoneProcess.heartbeat(replacementInstanceId, ZoneInstanceStatus.ACTIVE, 4);
        assertRoute(replacementInstanceId, "paper-reused");
    }

    private UUID registerStarting(String backendId, int softCapacity, int hardCapacity) throws SQLException {
        UUID instanceId = UUID.randomUUID();
        instances.registerStarting(instanceId, ZONE, "v1", backendId, softCapacity, hardCapacity);
        return instanceId;
    }

    private void assertRoute(UUID expectedInstanceId, String expectedBackendId) throws SQLException {
        ZoneRoute route = router.findPreferredActiveInstance(ZONE).orElseThrow();
        assertEquals(expectedInstanceId, route.instanceId());
        assertEquals(expectedBackendId, route.backendId());
        assertEquals(ZONE, route.zoneId());
    }

    private void staleInstanceHeartbeat(UUID instanceId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE zone_instances
                     SET last_heartbeat_at = NOW() - INTERVAL '2 minutes'
                     WHERE instance_id = ?
                     """)) {
            statement.setObject(1, instanceId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void staleBackendHeartbeat(String backendId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE backends
                     SET last_heartbeat_at = NOW() - INTERVAL '2 minutes'
                     WHERE backend_id = ?
                     """)) {
            statement.setString(1, backendId);
            assertEquals(1, statement.executeUpdate());
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
