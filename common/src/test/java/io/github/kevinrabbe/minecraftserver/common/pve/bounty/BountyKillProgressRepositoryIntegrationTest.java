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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BountyKillProgressRepositoryIntegrationTest {
    private static final BountyFamilyId FAMILY = new BountyFamilyId("zombie");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private BountyKillProgressRepository progress;

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
        progress = new BountyKillProgressRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        bounty_summons,
                        bounty_contracts,
                        processed_operations,
                        player_names,
                        player_state,
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
    void killWithoutActiveHuntIsPermanentNoOpAcrossLaterContract() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "NoHuntKill");
        UUID resourceKillOperation = UUID.randomUUID();
        UUID progressOperation = BountyKillProgressRepository.progressOperationId(resourceKillOperation);

        BountyKillProgressResult first = progress.recordManagedKill(
                progressOperation,
                playerId,
                FAMILY,
                1,
                "bounty.managed_kill"
        );
        assertFalse(first.applied());
        assertEquals(1L, processedCount(progressOperation));

        UUID laterContract = insertActiveContract(playerId, 3);
        BountyKillProgressResult replay = progress.recordManagedKill(
                progressOperation,
                playerId,
                FAMILY,
                1,
                "bounty.managed_kill"
        );

        assertEquals(first, replay);
        assertEquals(0, readProgress(laterContract));
        assertEquals(0L, readVersion(laterContract));
    }

    @Test
    void distinctManagedKillsAdvanceExactlyOnceAndUnlockSummon() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "HuntKill");
        UUID contractId = insertActiveContract(playerId, 2);

        UUID firstOperation = BountyKillProgressRepository.progressOperationId(UUID.randomUUID());
        BountyKillProgressResult first = progress.recordManagedKill(
                firstOperation, playerId, FAMILY, 1, "bounty.managed_kill"
        );
        BountyKillProgressResult retry = progress.recordManagedKill(
                firstOperation, playerId, FAMILY, 1, "bounty.managed_kill"
        );

        assertEquals(first, retry);
        assertTrue(first.applied());
        assertEquals(BountyContractStatus.ACTIVE_HUNT, first.contract().status());
        assertEquals(1, first.contract().eligibleKillProgress());
        assertEquals(1L, first.contract().stateVersion());

        BountyKillProgressResult second = progress.recordManagedKill(
                BountyKillProgressRepository.progressOperationId(UUID.randomUUID()),
                playerId,
                FAMILY,
                1,
                "bounty.managed_kill"
        );
        assertTrue(second.applied());
        assertEquals(BountyContractStatus.SUMMON_READY, second.contract().status());
        assertEquals(2, second.contract().eligibleKillProgress());
        assertEquals(1, second.contract().summonAuthorizationsRemaining());
        assertEquals(2L, second.contract().stateVersion());
        assertEquals(2, readProgress(contractId));
    }

    private UUID insertActiveContract(UUID playerId, int requiredKills) throws SQLException {
        UUID contractId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
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
                         state_version
                     ) VALUES (?, ?, ?, 1, 'ACTIVE_HUNT', 0, ?, 0, ?, 0)
                     """)) {
            statement.setObject(1, contractId);
            statement.setObject(2, playerId);
            statement.setString(3, FAMILY.value());
            statement.setInt(4, requiredKills);
            statement.setObject(5, UUID.randomUUID());
            statement.executeUpdate();
        }
        return contractId;
    }

    private int readProgress(UUID contractId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT eligible_kill_progress FROM bounty_contracts WHERE contract_id = ?
                     """)) {
            statement.setObject(1, contractId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private long readVersion(UUID contractId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT state_version FROM bounty_contracts WHERE contract_id = ?
                     """)) {
            statement.setObject(1, contractId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long processedCount(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM processed_operations WHERE operation_id = ?
                     """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
