package io.github.kevinrabbe.minecraftserver.common.session;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceStatus;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneRoute;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneRouter;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PostgreSqlCorrectnessIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration TICKET_LIFETIME = Duration.ofSeconds(30);
    private static final Duration HEARTBEAT_FRESHNESS = Duration.ofSeconds(30);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private BackendRegistry backends;
    private ZoneInstanceRegistry instances;
    private TransferRoutingRepository transferRouting;

    @BeforeAll
    void openDatabase() {
        DatabaseConfig config = new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                4
        );

        database = Database.open(config);
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);
        backends = new BackendRegistry(dataSource);
        instances = new ZoneInstanceRegistry(dataSource);
        transferRouting = new TransferRoutingRepository(dataSource, HEARTBEAT_FRESHNESS);
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
    void minecraftUuidMapsToOneStableInternalPlayerId() throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();

        UUID first = identities.ensurePlayer(minecraftUuid, "FirstName");
        UUID second = identities.ensurePlayer(minecraftUuid, "NewName");

        assertEquals(first, second);
        assertEquals(2, count("SELECT COUNT(*) FROM player_names WHERE player_id = ?", first));
        assertEquals(1, count("SELECT COUNT(*) FROM player_state WHERE player_id = ?", first));
    }

    @Test
    void secondLiveSessionIsRejectedUntilTheLeaseExpires() throws SQLException {
        UUID playerId = createPlayer("SessionTest");
        SessionLease first = sessions.openSession(playerId, "paper-a", null, LEASE);

        assertThrows(
                SessionConflictException.class,
                () -> sessions.openSession(playerId, "paper-b", null, LEASE)
        );

        expireSession(first.sessionId());
        SessionLease recovered = sessions.openSession(playerId, "paper-b", null, LEASE);

        assertNotEquals(first.sessionId(), recovered.sessionId());
        assertEquals(SessionStatus.ACTIVE, recovered.status());
        assertEquals("paper-b", recovered.ownerBackendId());
        assertEquals("DISCONNECTED", sessionStatus(first.sessionId()));
        assertThrows(
                SessionConflictException.class,
                () -> sessions.heartbeat(first.sessionId(), "paper-a", LEASE)
        );
    }

    @Test
    void staleStateVersionsCannotOverwriteNewerState() throws SQLException {
        UUID playerId = createPlayer("VersionTest");
        SessionLease lease = sessions.openSession(playerId, "paper-a", null, LEASE);

        long versionOne = states.commit(
                lease.sessionId(),
                "paper-a",
                0,
                "city",
                "spawn",
                new byte[]{1, 2, 3}
        );

        assertEquals(1, versionOne);
        assertThrows(
                SessionConflictException.class,
                () -> states.commit(
                        lease.sessionId(),
                        "paper-a",
                        0,
                        "city",
                        "spawn",
                        new byte[]{9}
                )
        );

        PlayerStateSnapshot snapshot = states.load(playerId);
        assertEquals(1, snapshot.stateVersion());
        assertEquals("city", snapshot.logicalZoneId());
        assertEquals("spawn", snapshot.entryPoint());
    }

    @Test
    void transferFreezesSourceWritesBindsRoutedTargetAndRejectsReplay() throws SQLException {
        backends.registerOnline("paper-source", 1);
        backends.registerOnline("paper-target", 0);

        UUID correctMineInstance = registerActiveInstance("mine", "paper-target", 2, 20, 25);
        UUID wrongForestInstance = registerActiveInstance("forest", "paper-target", 1, 20, 25);

        UUID playerId = createPlayer("TransferTest");
        SessionLease sourceLease = sessions.openSession(playerId, "paper-source", null, LEASE);
        long committedVersion = states.commit(
                sourceLease.sessionId(),
                "paper-source",
                sourceLease.stateVersion(),
                "city",
                "mine-gate",
                new byte[]{4, 5, 6}
        );

        TransferTicket ticket = sessions.beginTransfer(
                sourceLease.sessionId(),
                "paper-source",
                "mine",
                committedVersion,
                TICKET_LIFETIME
        );

        assertThrows(
                SessionConflictException.class,
                () -> states.commit(
                        sourceLease.sessionId(),
                        "paper-source",
                        committedVersion,
                        "mine",
                        "entry",
                        new byte[]{7}
                )
        );

        RoutedTransfer routed = transferRouting.route(ticket.transferId()).orElseThrow();
        assertEquals(correctMineInstance, routed.targetInstanceId());
        assertEquals("paper-target", routed.targetBackendId());
        assertEquals(ticket.transferId(), routed.transferId());

        assertThrows(
                SessionConflictException.class,
                () -> sessions.claimTransfer(ticket.transferId(), "paper-target", wrongForestInstance, LEASE)
        );

        SessionLease targetLease = sessions.claimTransfer(
                ticket.transferId(),
                "paper-target",
                correctMineInstance,
                LEASE
        );

        assertEquals("paper-target", targetLease.ownerBackendId());
        assertEquals(correctMineInstance, targetLease.ownerInstanceId());
        assertEquals(committedVersion, targetLease.stateVersion());

        assertThrows(
                SessionConflictException.class,
                () -> sessions.claimTransfer(ticket.transferId(), "paper-target", correctMineInstance, LEASE)
        );
        assertThrows(
                SessionConflictException.class,
                () -> sessions.heartbeat(sourceLease.sessionId(), "paper-source", LEASE)
        );

        long versionTwo = states.commit(
                targetLease.sessionId(),
                "paper-target",
                committedVersion,
                "mine",
                "entry",
                new byte[]{8, 9}
        );
        assertEquals(committedVersion + 1, versionTwo);
    }

    @Test
    void transferRoutingUsesMinecraftIdentityAndReroutesAnUnhealthyTarget() throws SQLException {
        backends.registerOnline("paper-source", 1);
        backends.registerOnline("paper-a", 0);
        backends.registerOnline("paper-b", 0);

        UUID firstInstance = registerActiveInstance("woods", "paper-a", 10, 20, 25);
        UUID secondInstance = registerActiveInstance("woods", "paper-b", 5, 20, 25);

        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, "RouteTest");
        SessionLease source = sessions.openSession(playerId, "paper-source", null, LEASE);
        TransferTicket ticket = sessions.beginTransfer(
                source.sessionId(),
                "paper-source",
                "woods",
                source.stateVersion(),
                TICKET_LIFETIME
        );

        RoutedTransfer firstRoute = transferRouting.routeForPlayer(minecraftUuid).orElseThrow();
        assertEquals(ticket.transferId(), firstRoute.transferId());
        assertEquals(firstInstance, firstRoute.targetInstanceId());
        assertEquals(firstRoute, transferRouting.findRoutedTransfer(minecraftUuid).orElseThrow());

        backends.markOffline("paper-a");
        RoutedTransfer rerouted = transferRouting.route(ticket.transferId()).orElseThrow();

        assertEquals(secondInstance, rerouted.targetInstanceId());
        assertEquals("paper-b", rerouted.targetBackendId());
        assertNotEquals(firstRoute.targetInstanceId(), rerouted.targetInstanceId());
    }

    @Test
    void transferRoutingReturnsEmptyWhenNoHealthyInstanceExists() throws SQLException {
        backends.registerOnline("paper-source", 1);

        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, "NoRoute");
        SessionLease source = sessions.openSession(playerId, "paper-source", null, LEASE);
        sessions.beginTransfer(
                source.sessionId(),
                "paper-source",
                "missing-zone",
                source.stateVersion(),
                TICKET_LIFETIME
        );

        assertTrue(transferRouting.routeForPlayer(minecraftUuid).isEmpty());
        assertTrue(transferRouting.findRoutedTransfer(minecraftUuid).isEmpty());
    }

    @Test
    void routerPacksHealthyInstancesAndIgnoresUnavailableBackends() throws SQLException {
        backends.registerOnline("paper-a", 20);
        backends.registerOnline("paper-b", 20);

        UUID sparse = registerActiveInstance("woods", "paper-a", 5, 20, 25);
        UUID preferred = registerActiveInstance("woods", "paper-a", 15, 20, 25);
        UUID aboveSoftCapacity = registerActiveInstance("woods", "paper-a", 22, 20, 25);
        UUID unavailable = registerActiveInstance("woods", "paper-b", 19, 20, 25);
        backends.markOffline("paper-b");

        ZoneRouter router = new ZoneRouter(dataSource, HEARTBEAT_FRESHNESS);
        Optional<ZoneRoute> route = router.findPreferredActiveInstance("woods");

        assertTrue(route.isPresent());
        assertEquals(preferred, route.orElseThrow().instanceId());
        assertNotEquals(sparse, route.orElseThrow().instanceId());
        assertNotEquals(aboveSoftCapacity, route.orElseThrow().instanceId());
        assertNotEquals(unavailable, route.orElseThrow().instanceId());
        assertFalse(router.findPreferredActiveInstance("missing-zone").isPresent());
    }

    private UUID createPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private UUID registerActiveInstance(
            String zoneId,
            String backendId,
            int playerCount,
            int softCapacity,
            int hardCapacity
    ) throws SQLException {
        UUID instanceId = UUID.randomUUID();
        instances.registerStarting(instanceId, zoneId, "v1", backendId, softCapacity, hardCapacity);
        instances.heartbeat(instanceId, ZoneInstanceStatus.ACTIVE, playerCount);
        return instanceId;
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

    private long count(String sql, UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
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
