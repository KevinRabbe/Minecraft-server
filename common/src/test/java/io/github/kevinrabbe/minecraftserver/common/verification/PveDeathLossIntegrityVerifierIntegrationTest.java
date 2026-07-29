package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.PveDeathLossRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PveDeathLossIntegrityVerifierIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;
    private PveDeathLossRepository deathLoss;
    private PveDeathLossIntegrityVerifier verifier;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                8
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        wallets = new CoinWalletRepository(dataSource);
        deathLoss = new PveDeathLossRepository(dataSource);
        verifier = new PveDeathLossIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE processed_operations, economic_ledger, players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void positiveAndZeroLossOutcomesReconcileCleanly() throws Exception {
        UUID positive = newPlayer("DeathVerifyA");
        UUID zero = newPlayer("DeathVerifyB");
        wallets.creditFromSystem(UUID.randomUUID(), positive, 40_000L, "test.seed");
        wallets.creditFromSystem(UUID.randomUUID(), zero, 40_000L, "test.seed");

        deathLoss.apply(
                UUID.randomUUID(), positive, "test.quarter_v1", balance -> balance / 4, "pve.death"
        );
        deathLoss.apply(
                UUID.randomUUID(), zero, "test.zero_v1", balance -> 0L, "pve.death"
        );

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void laterWalletActivityDoesNotInvalidateHistoricalDeathLoss() throws Exception {
        UUID playerId = newPlayer("DeathLater");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 40_000L, "test.seed");
        deathLoss.apply(
                UUID.randomUUID(), playerId, "test.quarter_v1", balance -> balance / 4, "pve.death"
        );
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 5_000L, "test.later");

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void malformedProcessedResultIsReportedWithoutVerifierFailure() throws Exception {
        UUID playerId = newPlayer("DeathMalformed");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 40_000L, "test.seed");
        UUID operationId = UUID.randomUUID();
        deathLoss.apply(
                operationId, playerId, "test.quarter_v1", balance -> balance / 4, "pve.death"
        );

        corrupt(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE processed_operations
                    SET result = jsonb_set(result, '{loss_minor}', '"bad"'::jsonb)
                    WHERE operation_id = ?
                    """)) {
                statement.setObject(1, operationId);
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue(operationId);
    }

    @Test
    void forgedLedgerAmountIsReported() throws Exception {
        UUID playerId = newPlayer("DeathLedger");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 40_000L, "test.seed");
        UUID operationId = UUID.randomUUID();
        deathLoss.apply(
                operationId, playerId, "test.quarter_v1", balance -> balance / 4, "pve.death"
        );

        corrupt(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE economic_ledger
                    SET amount = amount + 1
                    WHERE operation_id = ?
                    """)) {
                statement.setObject(1, operationId);
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue(operationId);
    }

    @Test
    void currentWalletBehindCommittedDeathVersionIsReported() throws Exception {
        UUID playerId = newPlayer("DeathVersion");
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 40_000L, "test.seed");
        UUID operationId = UUID.randomUUID();
        deathLoss.apply(
                operationId, playerId, "test.quarter_v1", balance -> balance / 4, "pve.death"
        );

        corrupt(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE wallets
                    SET state_version = state_version - 1
                    WHERE player_id = ?
                    """)) {
                statement.setObject(1, playerId);
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue(operationId);
    }

    private UUID newPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void assertIssue(UUID operationId) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("PVE_DEATH_LOSS_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(operationId.toString())), issues.toString());
    }

    private void corrupt(SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }
}
