package io.github.kevinrabbe.minecraftserver.common.item;

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

import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class UniqueItemAuthorityDatabaseInvariantTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private UniqueItemAuthorityRepository items;

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
        items = new UniqueItemAuthorityRepository(
                dataSource,
                new ItemCatalog(List.of(new ItemDefinition(
                        "equipment.test_sword",
                        "IRON_SWORD",
                        "Test Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                )))
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        item_provenance,
                        item_instances,
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
    void directSqlCannotAssignPlayerInventoryAuthorityToUnknownPlayer() throws SQLException {
        UUID originalOwner = identities.ensurePlayer(UUID.randomUUID(), "Original");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO item_instances(
                         item_instance_id,
                         definition_id,
                         location_kind,
                         location_id,
                         state_version,
                         original_owner_player_id,
                         created_by_operation_id,
                         created_reason
                     )
                     VALUES (?, 'equipment.test_sword', 'PLAYER_INVENTORY', ?, 0, ?, ?, 'test.direct')
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, originalOwner);
            statement.setObject(4, UUID.randomUUID());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void provenanceRowsCannotBeUpdatedOrDeleted() throws SQLException {
        UUID owner = identities.ensurePlayer(UUID.randomUUID(), "Owner");
        UniqueItemAuthorityResult created = items.createForPlayer(
                UUID.randomUUID(),
                "equipment.test_sword",
                owner,
                "test.create",
                owner
        );

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE item_provenance
                    SET reason = 'test.tampered'
                    WHERE item_instance_id = ?
                    """)) {
                update.setObject(1, created.itemInstanceId());
                assertThrows(SQLException.class, update::executeUpdate);
            }

            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM item_provenance
                    WHERE item_instance_id = ?
                    """)) {
                delete.setObject(1, created.itemInstanceId());
                assertThrows(SQLException.class, delete::executeUpdate);
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
