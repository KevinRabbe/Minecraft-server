package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerZoneRoutingRepository;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveProxyReturnProjectionIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerZoneRoutingRepository playerZones;
    private CompetitiveRuntimeTopologyRepository runtimeTopology;

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
        playerZones = new PlayerZoneRoutingRepository(dataSource);
        runtimeTopology = new CompetitiveRuntimeTopologyRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        competitive_player_execution_reservations,
                        competitive_runtime_principals,
                        competitive_execution_participants,
                        competitive_execution_specs,
                        competitive_result_reports,
                        competitive_executions,
                        clan_war_results,
                        clan_war_items,
                        clan_war_rosters,
                        clan_wars,
                        clan_war_ratings,
                        ranked_match_results,
                        ranked_match_participants,
                        ranked_matches,
                        ranked_ratings,
                        processed_operations,
                        transfer_tickets,
                        player_sessions,
                        player_state,
                        player_names,
                        wallets,
                        players,
                        backends
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void durableReturnProjectionReadsLogicalZoneWithoutStatePayload() throws Exception {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, "ReturnRoute");
        setPlayerState(playerId, "city", new byte[]{1, 2, 3, 4});

        assertEquals("city", playerZones.findLogicalZone(minecraftUuid).orElseThrow());
        assertTrue(playerZones.findLogicalZone(UUID.randomUUID()).isEmpty());

        setPlayerState(playerId, null, new byte[]{9, 8, 7});
        assertTrue(playerZones.findLogicalZone(minecraftUuid).isEmpty());
    }

    @Test
    void runtimeTopologyContainsOnlyConfiguredCompetitiveBackendIds() throws Exception {
        runtimePrincipal("legacy-return-b");
        runtimePrincipal("legacy-return-a");

        assertEquals(Set.of("legacy-return-a", "legacy-return-b"), runtimeTopology.findBackendIds());
    }

    private void setPlayerState(UUID playerId, String logicalZoneId, byte[] payload) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_state
                     SET logical_zone_id = ?, state_payload = ?
                     WHERE player_id = ?
                     """)) {
            statement.setString(1, logicalZoneId);
            statement.setBytes(2, payload);
            statement.setObject(3, playerId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void runtimePrincipal(String backendId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO competitive_runtime_principals(
                         database_role,
                         backend_id,
                         max_execution_lease_seconds,
                         dispatch_enabled,
                         max_active_executions
                     ) VALUES (?, ?, 120, TRUE, 4)
                     """)) {
            statement.setString(1, "return-role-" + backendId);
            statement.setString(2, backendId);
            statement.executeUpdate();
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
