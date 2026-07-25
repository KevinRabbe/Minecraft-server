package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PaperResourceSessionResolverIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;

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
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        player_sessions,
                        zone_instances,
                        backends,
                        player_names,
                        wallets,
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
    void resolvesOnlyUnexpiredInstanceAttachedSessionOwnedByThisBackend() throws Exception {
        PaperResourceSessionResolver resolver = new PaperResourceSessionResolver(dataSource, "paper-a");

        UUID validMinecraft = UUID.randomUUID();
        UUID validPlayer = identities.ensurePlayer(validMinecraft, "ResolverValid");
        UUID instanceA = createInstance("paper-a");
        SessionLease valid = sessions.openSession(validPlayer, "paper-a", instanceA, LEASE);

        PaperResourceSessionResolver.ResourceSessionHint hint = resolver.resolve(validMinecraft).orElseThrow();
        assertEquals(valid.sessionId(), hint.sessionId());
        assertEquals(validPlayer, hint.playerId());
        assertEquals(instanceA, hint.instanceId());
        assertEquals(valid.stateVersion(), hint.stateVersion());

        UUID noInstanceMinecraft = UUID.randomUUID();
        UUID noInstancePlayer = identities.ensurePlayer(noInstanceMinecraft, "ResolverNoInst");
        sessions.openSession(noInstancePlayer, "paper-a", null, LEASE);
        assertTrue(resolver.resolve(noInstanceMinecraft).isEmpty());

        UUID wrongBackendMinecraft = UUID.randomUUID();
        UUID wrongBackendPlayer = identities.ensurePlayer(wrongBackendMinecraft, "ResolverWrong");
        UUID instanceB = createInstance("paper-b");
        sessions.openSession(wrongBackendPlayer, "paper-b", instanceB, LEASE);
        assertTrue(resolver.resolve(wrongBackendMinecraft).isEmpty());

        UUID expiredMinecraft = UUID.randomUUID();
        UUID expiredPlayer = identities.ensurePlayer(expiredMinecraft, "ResolverExpire");
        SessionLease expired = sessions.openSession(expiredPlayer, "paper-a", instanceA, LEASE);
        expire(expired.sessionId());
        assertTrue(resolver.resolve(expiredMinecraft).isEmpty());
    }

    private UUID createInstance(String backendId) throws SQLException {
        UUID instanceId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement backend = connection.prepareStatement("""
                    INSERT INTO backends(backend_id, status)
                    VALUES (?, 'ONLINE')
                    ON CONFLICT (backend_id) DO NOTHING
                    """)) {
                backend.setString(1, backendId);
                backend.executeUpdate();
            }
            try (PreparedStatement instance = connection.prepareStatement("""
                    INSERT INTO zone_instances(
                        instance_id, zone_id, template_version, backend_id, status,
                        player_count, soft_capacity, hard_capacity
                    ) VALUES (?, 'starter_mine', 'v1', ?, 'ACTIVE', 0, 20, 30)
                    """)) {
                instance.setObject(1, instanceId);
                instance.setString(2, backendId);
                instance.executeUpdate();
            }
        }
        return instanceId;
    }

    private void expire(UUID sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_sessions
                     SET lease_expires_at = NOW() - INTERVAL '1 second'
                     WHERE network_session_id = ?
                     """)) {
            statement.setObject(1, sessionId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
