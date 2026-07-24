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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BackendSessionLeaseRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
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
    void heartbeatRenewsOnlyAttachedValidSessionsOwnedByTheBackend() throws SQLException {
        SessionLease valid = openSession("Valid", "paper-a");
        SessionLease expired = openSession("Expired", "paper-a");
        SessionLease otherBackend = openSession("OtherBackend", "paper-b");
        expireSession(expired.sessionId());

        Set<UUID> renewed = backendLeases.heartbeatAttachedSessions(
                "paper-a",
                Set.of(valid.sessionId(), expired.sessionId(), otherBackend.sessionId()),
                LEASE
        );

        assertEquals(Set.of(valid.sessionId()), renewed);
        assertTrue(leaseIsValid(valid.sessionId()));
        assertFalse(leaseIsValid(expired.sessionId()));
        assertTrue(leaseIsValid(otherBackend.sessionId()));
    }

    @Test
    void sourceQuitCannotCancelAnInFlightTransfer() throws SQLException {
        SessionLease source = openSession("Transfer", "paper-source");
        TransferTicket ticket = sessions.beginTransfer(
                source.sessionId(),
                "paper-source",
                "mine",
                source.stateVersion(),
                Duration.ofSeconds(30)
        );

        boolean disconnected = backendLeases.disconnectAttachedSession(source.sessionId(), "paper-source");

        assertFalse(disconnected);
        assertEquals("TRANSFERRING", sessionStatus(source.sessionId()));
        assertEquals(0, transferConsumedCount(ticket.transferId()));
    }

    @Test
    void controlledShutdownReleasesOrdinarySessionsButPreservesTransfers() throws SQLException {
        SessionLease active = openSession("Active", "paper-a");
        SessionLease transferring = openSession("Transferring", "paper-a");
        SessionLease foreign = openSession("Foreign", "paper-b");

        sessions.beginTransfer(
                transferring.sessionId(),
                "paper-a",
                "woods",
                transferring.stateVersion(),
                Duration.ofSeconds(30)
        );

        Set<UUID> disconnected = backendLeases.disconnectAttachedSessions(
                "paper-a",
                Set.of(active.sessionId(), transferring.sessionId(), foreign.sessionId())
        );

        assertEquals(Set.of(active.sessionId()), disconnected);
        assertEquals("DISCONNECTED", sessionStatus(active.sessionId()));
        assertEquals("TRANSFERRING", sessionStatus(transferring.sessionId()));
        assertEquals("ACTIVE", sessionStatus(foreign.sessionId()));
    }

    private SessionLease openSession(String name, String backendId) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        return sessions.openSession(playerId, backendId, null, LEASE);
    }

    private void expireSession(UUID sessionId) throws SQLException {
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

    private boolean leaseIsValid(UUID sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT lease_expires_at IS NOT NULL AND lease_expires_at > NOW() AS valid
                     FROM player_sessions
                     WHERE network_session_id = ?
                     """)) {
            statement.setObject(1, sessionId);
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                return results.getBoolean("valid");
            }
        }
    }

    private String sessionStatus(UUID sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT status
                     FROM player_sessions
                     WHERE network_session_id = ?
                     """)) {
            statement.setObject(1, sessionId);
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                return results.getString("status");
            }
        }
    }

    private long transferConsumedCount(UUID transferId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM transfer_tickets
                     WHERE transfer_id = ? AND consumed_at IS NOT NULL
                     """)) {
            statement.setObject(1, transferId);
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                return results.getLong(1);
            }
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
