package io.github.kevinrabbe.minecraftserver.common.session;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PlayerStatePersistenceIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private BackendSessionLeaseRepository backendLeases;

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
        backendLeases = new BackendSessionLeaseRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
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
    void committedPayloadAndLogicalLocationSurviveCleanReconnect() throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, "CheckpointTest");
        SessionLease first = sessions.openSession(playerId, "paper-a", null, LEASE);

        byte[] payload = new byte[]{10, 20, 30, 40};
        long committedVersion = states.commit(
                first.sessionId(),
                "paper-a",
                first.stateVersion(),
                "city",
                "spawn",
                payload
        );
        backendLeases.disconnectAttachedSession(first.sessionId(), "paper-a");

        SessionLease second = sessions.openSession(playerId, "paper-b", null, LEASE);
        PlayerStateSnapshot loaded = states.load(playerId);

        assertEquals(committedVersion, second.stateVersion());
        assertEquals(committedVersion, loaded.stateVersion());
        assertEquals("city", loaded.logicalZoneId());
        assertEquals("spawn", loaded.entryPoint());
        assertArrayEquals(payload, loaded.statePayload());
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }
}
