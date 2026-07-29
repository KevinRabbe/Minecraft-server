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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BackendZoneInstanceBindingRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private BackendRegistry backends;
    private ZoneInstanceRegistry instances;
    private BackendZoneInstanceBindingRepository bindings;

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
        bindings = new BackendZoneInstanceBindingRepository(dataSource, Duration.ofMinutes(1));
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE backends RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void resolvesTheSingleFreshActiveBootstrapInstance() throws Exception {
        String backendId = "paper-city";
        String zoneId = "city";
        UUID instanceId = activeInstance(backendId, zoneId);

        assertEquals(instanceId, bindings.findSingleFreshActiveInstance(backendId, zoneId).orElseThrow());
    }

    @Test
    void ignoresDifferentZoneOnSameBackend() throws Exception {
        String backendId = "paper-city";
        UUID city = activeInstance(backendId, "city");
        activeInstance(backendId, "starter-woods");

        assertEquals(city, bindings.findSingleFreshActiveInstance(backendId, "city").orElseThrow());
    }

    @Test
    void stoppedInstanceIsNotBindable() throws Exception {
        String backendId = "paper-city";
        String zoneId = "city";
        UUID instanceId = activeInstance(backendId, zoneId);
        instances.markStopped(instanceId);

        assertTrue(bindings.findSingleFreshActiveInstance(backendId, zoneId).isEmpty());
    }

    @Test
    void multipleFreshInstancesFailClosedInsteadOfGuessing() throws Exception {
        String backendId = "paper-city";
        String zoneId = "city";
        activeInstance(backendId, zoneId);
        activeInstance(backendId, zoneId);

        assertThrows(
                SessionConflictException.class,
                () -> bindings.findSingleFreshActiveInstance(backendId, zoneId)
        );
    }

    private UUID activeInstance(String backendId, String zoneId) throws SQLException {
        backends.registerOnline(backendId, 0);
        UUID instanceId = UUID.randomUUID();
        instances.registerStarting(instanceId, zoneId, "test-v1", backendId, 20, 25);
        instances.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, 0);
        return instanceId;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
