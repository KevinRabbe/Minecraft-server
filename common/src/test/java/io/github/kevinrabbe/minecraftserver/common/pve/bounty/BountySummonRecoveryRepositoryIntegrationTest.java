package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BountySummonRecoveryRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private BountySummonRecoveryRepository recovery;

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
        recovery = new BountySummonRecoveryRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        bounty_boss_materializations,
                        bounty_summons,
                        bounty_contracts,
                        processed_operations,
                        player_names,
                        player_state,
                        wallets,
                        players,
                        backends
                    RESTART IDENTITY CASCADE
                    """);
            statement.execute("INSERT INTO backends(backend_id, status) VALUES ('paper-a', 'ONLINE')");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void returnsOnlyAbandonedReadyAndExpiredActiveSummons() throws Exception {
        UUID oldReady = insertSummon("RecoveryOldReady", "READY", "2 minutes", null);
        UUID freshReady = insertSummon("RecoveryFreshReady", "READY", "5 seconds", null);
        UUID expiredActive = insertSummon("RecoveryExpired", "ACTIVE", "2 minutes", "-5 seconds");
        UUID liveActive = insertSummon("RecoveryLive", "ACTIVE", "2 minutes", "5 minutes");

        List<UUID> result = recovery.listRecoverable(Duration.ofSeconds(30), 10);

        assertEquals(2, result.size());
        assertTrue(result.contains(oldReady));
        assertTrue(result.contains(expiredActive));
        assertTrue(!result.contains(freshReady));
        assertTrue(!result.contains(liveActive));
    }

    @Test
    void resultIsBoundedAndOldestFirst() throws Exception {
        UUID oldest = insertSummon("RecoveryOldest", "READY", "3 minutes", null);
        UUID middle = insertSummon("RecoveryMiddle", "READY", "2 minutes", null);
        insertSummon("RecoveryNewest", "READY", "1 minute", null);

        List<UUID> result = recovery.listRecoverable(Duration.ofSeconds(30), 2);

        assertEquals(List.of(oldest, middle), result);
    }

    @Test
    void rejectsUnboundedRecoveryQueries() {
        assertThrows(IllegalArgumentException.class, () -> recovery.listRecoverable(Duration.ofSeconds(-1), 1));
        assertThrows(IllegalArgumentException.class, () -> recovery.listRecoverable(Duration.ofMinutes(11), 1));
        assertThrows(IllegalArgumentException.class, () -> recovery.listRecoverable(Duration.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> recovery.listRecoverable(Duration.ZERO, 101));
    }

    private UUID insertSummon(String playerName, String status, String createdAgo, String leaseOffset)
            throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), playerName);
        UUID contractId = UUID.randomUUID();
        UUID summonId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement contract = connection.prepareStatement("""
                    INSERT INTO bounty_contracts(
                        contract_id,
                        player_id,
                        family_id,
                        tier,
                        status,
                        eligible_kill_progress,
                        required_eligible_kills,
                        summon_authorizations_remaining,
                        fee_operation_id,
                        state_version,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, 'zombie', 1, 'SUMMONED', 10, 10, 0, ?, 2,
                              NOW() - (?::interval), NOW() - (?::interval))
                    """)) {
                contract.setObject(1, contractId);
                contract.setObject(2, playerId);
                contract.setObject(3, UUID.randomUUID());
                contract.setString(4, createdAgo);
                contract.setString(5, createdAgo);
                contract.executeUpdate();
            }

            if ("READY".equals(status)) {
                try (PreparedStatement summon = connection.prepareStatement("""
                        INSERT INTO bounty_summons(summon_id, contract_id, status, state_version, created_at)
                        VALUES (?, ?, 'READY', 0, NOW() - (?::interval))
                        """)) {
                    summon.setObject(1, summonId);
                    summon.setObject(2, contractId);
                    summon.setString(3, createdAgo);
                    summon.executeUpdate();
                }
            } else if ("ACTIVE".equals(status)) {
                try (PreparedStatement summon = connection.prepareStatement("""
                        INSERT INTO bounty_summons(
                            summon_id,
                            contract_id,
                            status,
                            owner_backend_id,
                            lease_expires_at,
                            state_version,
                            created_at,
                            activated_at
                        ) VALUES (?, ?, 'ACTIVE', 'paper-a', NOW() + (?::interval), 1,
                                  NOW() - (?::interval), NOW() - (?::interval))
                        """)) {
                    summon.setObject(1, summonId);
                    summon.setObject(2, contractId);
                    summon.setString(3, leaseOffset);
                    summon.setString(4, createdAgo);
                    summon.setString(5, createdAgo);
                    summon.executeUpdate();
                }
            } else {
                throw new IllegalArgumentException("unsupported status: " + status);
            }
        }
        return summonId;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
