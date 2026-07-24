package io.github.kevinrabbe.minecraftserver.common.economy;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class EconomicEvidenceDatabaseInvariantTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;

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
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        item_provenance,
                        item_instances,
                        wallets,
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
    void processedOperationCannotBeUpdatedOrDeleted() throws SQLException {
        UUID operationId = UUID.randomUUID();
        insertProcessedOperation(operationId);

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE processed_operations
                    SET operation_type = 'changed'
                    WHERE operation_id = ?
                    """)) {
                update.setObject(1, operationId);
                assertThrows(SQLException.class, update::executeUpdate);
            }

            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM processed_operations
                    WHERE operation_id = ?
                    """)) {
                delete.setObject(1, operationId);
                assertThrows(SQLException.class, delete::executeUpdate);
            }
        }
    }

    @Test
    void ledgerEntryCannotBeUpdatedOrDeleted() throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "LedgerOwner");
        UUID operationId = UUID.randomUUID();
        insertLedgerEntry(operationId, playerId, 100, "test.evidence");

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE economic_ledger
                    SET amount = 200
                    WHERE operation_id = ?
                    """)) {
                update.setObject(1, operationId);
                assertThrows(SQLException.class, update::executeUpdate);
            }

            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM economic_ledger
                    WHERE operation_id = ?
                    """)) {
                delete.setObject(1, operationId);
                assertThrows(SQLException.class, delete::executeUpdate);
            }
        }
    }

    @Test
    void ledgerRejectsZeroAmountAndBlankEvidenceFields() throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "EvidenceShape");

        assertThrows(
                SQLException.class,
                () -> insertLedgerEntry(UUID.randomUUID(), playerId, 0, "test.zero")
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO economic_ledger(
                         operation_id,
                         line_no,
                         player_id,
                         asset_type,
                         asset_id,
                         amount,
                         direction,
                         reason
                     )
                     VALUES (?, 0, ?, '', 'coin', 1, 'CREDIT', 'test.blank')
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, playerId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void processedOperationTypeCannotBeBlank() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO processed_operations(operation_id, operation_type, result)
                     VALUES (?, '   ', '{}'::jsonb)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private void insertProcessedOperation(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO processed_operations(operation_id, operation_type, result)
                     VALUES (?, 'test.operation', '{}'::jsonb)
                     """)) {
            statement.setObject(1, operationId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertLedgerEntry(
            UUID operationId,
            UUID playerId,
            long amount,
            String reason
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO economic_ledger(
                         operation_id,
                         line_no,
                         player_id,
                         asset_type,
                         asset_id,
                         amount,
                         direction,
                         reason
                     )
                     VALUES (?, 0, ?, 'CURRENCY', 'coin', ?, 'CREDIT', ?)
                     """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, playerId);
            statement.setLong(3, amount);
            statement.setString(4, reason);
            assertEquals(1, statement.executeUpdate());
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
