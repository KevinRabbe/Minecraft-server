package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import io.github.kevinrabbe.minecraftserver.common.session.TransferTicket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PlayerSessionIntegrityVerifierIntegrationTest {
    private static final String BACKEND = "verify-session";
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration TICKET_LIFETIME = Duration.ofMinutes(1);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private PlayerSessionIntegrityVerifier verifier;

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
        verifier = new PlayerSessionIntegrityVerifier(dataSource);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void healthyLiveSessionMatchesPersistentPlayerState() throws Exception {
        UUID playerId = player("SessHealthy");
        SessionLease session = sessions.openSession(playerId, BACKEND, null, LEASE);

        assertFalse(verifier.verify(10_000).stream().anyMatch(issue ->
                session.sessionId().toString().equals(issue.subjectId())
        ));
    }

    @Test
    void liveSessionVersionDivergenceIsCritical() throws Exception {
        UUID playerId = player("SessVersion");
        SessionLease session = sessions.openSession(playerId, BACKEND, null, LEASE);
        setSessionVersion(session.sessionId(), session.stateVersion() + 1L);
        try {
            assertTrue(verifier.verify(10_000).stream().anyMatch(issue ->
                    issue.severity() == IntegritySeverity.CRITICAL
                            && issue.code().equals("LIVE_SESSION_STATE_VERSION_MISMATCH")
                            && session.sessionId().toString().equals(issue.subjectId())
            ));
        } finally {
            setSessionVersion(session.sessionId(), session.stateVersion());
        }
    }

    @Test
    void disconnectedSessionCannotRetainBackendOrLeaseCustody() throws Exception {
        UUID playerId = player("SessShape");
        SessionLease session = sessions.openSession(playerId, BACKEND, null, LEASE);
        sessions.disconnect(session.sessionId(), BACKEND);
        setDisconnectedCustody(session.sessionId(), "stale-backend");
        try {
            assertTrue(verifier.verify(10_000).stream().anyMatch(issue ->
                    issue.severity() == IntegritySeverity.CRITICAL
                            && issue.code().equals("SESSION_LIFECYCLE_SHAPE_INVALID")
                            && session.sessionId().toString().equals(issue.subjectId())
            ));
        } finally {
            clearDisconnectedCustody(session.sessionId());
        }
    }

    @Test
    void historicalDisconnectedSessionMayBeOlderThanCurrentPlayerState() throws Exception {
        UUID playerId = player("SessHistory");
        SessionLease first = sessions.openSession(playerId, BACKEND, null, LEASE);
        long firstCommittedVersion = states.commit(
                first.sessionId(), BACKEND, first.stateVersion(), null, null, null
        );
        assertEquals(1L, firstCommittedVersion);
        sessions.disconnect(first.sessionId(), BACKEND);

        SessionLease second = sessions.openSession(playerId, BACKEND, null, LEASE);
        long secondCommittedVersion = states.commit(
                second.sessionId(), BACKEND, second.stateVersion(), null, null, null
        );
        assertEquals(2L, secondCommittedVersion);

        assertFalse(verifier.verify(10_000).stream().anyMatch(issue ->
                first.sessionId().toString().equals(issue.subjectId())
                        || second.sessionId().toString().equals(issue.subjectId())
        ));
    }

    @Test
    void transferringSessionMatchesItsOneOpenTicket() throws Exception {
        UUID playerId = player("SessTransfer");
        SessionLease session = sessions.openSession(playerId, BACKEND, null, LEASE);
        TransferTicket ticket = sessions.beginTransfer(
                session.sessionId(), BACKEND, "verify-zone", session.stateVersion(), TICKET_LIFETIME
        );

        assertFalse(verifier.verify(10_000).stream().anyMatch(issue ->
                session.sessionId().toString().equals(issue.subjectId())
                        || ticket.transferId().toString().equals(issue.subjectId())
        ));
    }

    @Test
    void transferTicketVersionDivergenceIsCritical() throws Exception {
        UUID playerId = player("SessTicketVer");
        SessionLease session = sessions.openSession(playerId, BACKEND, null, LEASE);
        TransferTicket ticket = sessions.beginTransfer(
                session.sessionId(), BACKEND, "verify-zone", session.stateVersion(), TICKET_LIFETIME
        );
        setTicketExpectedVersion(ticket.transferId(), session.stateVersion() + 1L);
        try {
            assertTrue(verifier.verify(10_000).stream().anyMatch(issue ->
                    issue.severity() == IntegritySeverity.CRITICAL
                            && issue.code().equals("TRANSFERRING_SESSION_TICKET_MISMATCH")
                            && session.sessionId().toString().equals(issue.subjectId())
            ));
        } finally {
            setTicketExpectedVersion(ticket.transferId(), session.stateVersion());
        }
    }

    @Test
    void openTicketCannotPointAtNonTransferringSession() throws Exception {
        UUID playerId = player("SessTicketState");
        SessionLease session = sessions.openSession(playerId, BACKEND, null, LEASE);
        TransferTicket ticket = sessions.beginTransfer(
                session.sessionId(), BACKEND, "verify-zone", session.stateVersion(), TICKET_LIFETIME
        );
        setSessionStatus(session.sessionId(), "ACTIVE");
        try {
            assertTrue(verifier.verify(10_000).stream().anyMatch(issue ->
                    issue.severity() == IntegritySeverity.CRITICAL
                            && issue.code().equals("OPEN_TRANSFER_TICKET_SESSION_MISMATCH")
                            && ticket.transferId().toString().equals(issue.subjectId())
            ));
        } finally {
            setSessionStatus(session.sessionId(), "TRANSFERRING");
        }
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void setSessionVersion(UUID sessionId, long stateVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_sessions
                     SET state_version = ?
                     WHERE network_session_id = ?
                     """)) {
            statement.setLong(1, stateVersion);
            statement.setObject(2, sessionId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void setDisconnectedCustody(UUID sessionId, String backendId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_sessions
                     SET owner_backend_id = ?,
                         lease_expires_at = NOW() + INTERVAL '5 minutes'
                     WHERE network_session_id = ?
                     """)) {
            statement.setString(1, backendId);
            statement.setObject(2, sessionId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void clearDisconnectedCustody(UUID sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_sessions
                     SET owner_backend_id = NULL,
                         owner_instance_id = NULL,
                         lease_expires_at = NULL
                     WHERE network_session_id = ?
                     """)) {
            statement.setObject(1, sessionId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void setTicketExpectedVersion(UUID transferId, long stateVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE transfer_tickets
                     SET expected_state_version = ?
                     WHERE transfer_id = ?
                     """)) {
            statement.setLong(1, stateVersion);
            statement.setObject(2, transferId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void setSessionStatus(UUID sessionId, String status) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_sessions
                     SET status = ?
                     WHERE network_session_id = ?
                     """)) {
            statement.setString(1, status);
            statement.setObject(2, sessionId);
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
