package io.github.kevinrabbe.minecraftserver.common.session;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceStatus;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneRoute;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PersistentMmoLoginRouterIntegrationTest {
    private static final String HUB = "city";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private BackendRegistry backends;
    private ZoneInstanceRegistry instances;
    private PersistentMmoLoginRouter router;

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
        backends = new BackendRegistry(dataSource);
        instances = new ZoneInstanceRegistry(dataSource);
        router = new PersistentMmoLoginRouter(dataSource, Duration.ofMinutes(1));
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE players, backends RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void freshPlayerRoutesToPersistentHub() throws Exception {
        ZoneRoute hub = activeZone(HUB, "paper-city");

        Optional<ZoneRoute> route = router.findInitialRoute(UUID.randomUUID(), HUB);

        assertTrue(route.isPresent());
        assertEquals(hub, route.orElseThrow());
    }

    @Test
    void returningPlayerRestoresHealthySavedLogicalZone() throws Exception {
        activeZone(HUB, "paper-city");
        ZoneRoute woods = activeZone("starter-woods", "paper-woods");
        UUID minecraftUuid = playerWithSavedZone("Returner", "starter-woods");

        Optional<ZoneRoute> route = router.findInitialRoute(minecraftUuid, HUB);

        assertEquals(woods, route.orElseThrow());
    }

    @Test
    void unavailableSavedZoneFallsBackToHub() throws Exception {
        ZoneRoute hub = activeZone(HUB, "paper-city");
        UUID minecraftUuid = playerWithSavedZone("Fallback", "starter-woods");

        Optional<ZoneRoute> route = router.findInitialRoute(minecraftUuid, HUB);

        assertEquals(hub, route.orElseThrow());
    }

    @Test
    void missingHubDoesNotRouteFreshPlayerToUnrelatedZone() throws Exception {
        activeZone("starter-woods", "paper-woods");

        Optional<ZoneRoute> route = router.findInitialRoute(UUID.randomUUID(), HUB);

        assertTrue(route.isEmpty());
    }

    @Test
    void unavailableSavedHubDoesNotRouteToUnrelatedZone() throws Exception {
        activeZone("starter-woods", "paper-woods");
        UUID minecraftUuid = playerWithSavedZone("HubReturn", HUB);

        Optional<ZoneRoute> route = router.findInitialRoute(minecraftUuid, HUB);

        assertTrue(route.isEmpty());
    }

    private ZoneRoute activeZone(String zoneId, String backendId) throws SQLException {
        backends.registerOnline(backendId, 0);
        UUID instanceId = UUID.randomUUID();
        instances.registerStarting(instanceId, zoneId, "test-v1", backendId, 20, 25);
        instances.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, 0);
        return new ZoneRoute(zoneId, instanceId, backendId);
    }

    private UUID playerWithSavedZone(String name, String zoneId) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, name);
        SessionLease lease = sessions.openSession(playerId, "state-owner", null, Duration.ofMinutes(5));
        states.commit(
                lease.sessionId(),
                "state-owner",
                lease.stateVersion(),
                zoneId,
                "default",
                null
        );
        sessions.disconnect(lease.sessionId(), "state-owner");
        return minecraftUuid;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
