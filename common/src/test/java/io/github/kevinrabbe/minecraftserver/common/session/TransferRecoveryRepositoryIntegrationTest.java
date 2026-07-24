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
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class TransferRecoveryRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private TransferRecoveryRepository recovery;

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
        recovery = new TransferRecoveryRepository(dataSource);
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
    void expiredUnclaimedTransferReturnsAttachedSourceSessionToActive() throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "Recovery");
        SessionLease source = sessions.openSession(playerId, "paper-source", null, LEASE);
        TransferTicket ticket = sessions.beginTransfer(
                source.sessionId(),
                "paper-source",
                "woods",
                source.stateVersion(),
                Duration.ofSeconds(30)
        );
        expireTransfer(ticket.transferId());

        Set<UUID> recovered = recovery.recoverExpiredAttachedTransfers(
                "paper-source",
                Set.of(source.sessionId())
        );

        assertEquals(Set.of(source.sessionId()), recovered);
        assertEquals("ACTIVE", sessionStatus(source.sessionId()));
        assertTrue(ticketIsClosed(ticket.transferId()));

        long nextVersion = states.commit(
                source.sessionId(),
                "paper-source",
                source.stateVersion(),
                "city",
                "spawn",
                new byte[]{1}
        );
        assertEquals(source.stateVersion() + 1, nextVersion);
    }

    @Test
    void expiredTransferIsNotRecoveredByABackendThatDoesNotOwnTheAttachedSession() throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "WrongOwner");
        SessionLease source = sessions.openSession(playerId, "paper-source", null, LEASE);
        TransferTicket ticket = sessions.beginTransfer(
                source.sessionId(),
                "paper-source",
                "woods",
                source.stateVersion(),
                Duration.ofSeconds(30)
        );
        expireTransfer(ticket.transferId());

        assertTrue(recovery.recoverExpiredAttachedTransfers(
                "paper-other",
                Set.of(source.sessionId())
        ).isEmpty());
        assertEquals("TRANSFERRING", sessionStatus(source.sessionId()));
    }

    private void expireTransfer(UUID transferId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE transfer_tickets
                     SET expires_at = NOW() - INTERVAL '1 second'
                     WHERE transfer_id = ?
                     """)) {
            statement.setObject(1, transferId);
            assertEquals(1, statement.executeUpdate());
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

    private boolean ticketIsClosed(UUID transferId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT consumed_at IS NOT NULL AS closed
                     FROM transfer_tickets
                     WHERE transfer_id = ?
                     """)) {
            statement.setObject(1, transferId);
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                return results.getBoolean("closed");
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
