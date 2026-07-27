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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ItemUpgradeDatabaseInvariantTest {
    private static final String SWORD = "equipment.upgrade_db_test";
    private static final String REASON = "test.item_upgrade_db";

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
        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                SWORD,
                "IRON_SWORD",
                "Upgrade DB Test Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        item_upgrade_events,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
                        player_state,
                        player_names,
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
    void nakedUpgradeCannotCommitEvenWithForgedUpgradeProvenance() throws Exception {
        UUID owner = player("UpgradeDbRaw");
        UniqueItemAuthorityResult item = createItem(owner);
        UUID operationId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE item_instances
                        SET upgrade_level = 1,
                            state_version = 1,
                            updated_at = NOW()
                        WHERE item_instance_id = ?
                        """)) {
                    update.setObject(1, item.itemInstanceId());
                    assertEquals(1, update.executeUpdate());
                }
                try (PreparedStatement provenance = connection.prepareStatement("""
                        INSERT INTO item_provenance(
                            item_instance_id,
                            sequence_no,
                            operation_id,
                            event_type,
                            from_location_kind,
                            from_location_id,
                            to_location_kind,
                            to_location_id,
                            reason,
                            actor_player_id
                        ) VALUES (?, 1, ?, 'UPGRADED', 'PLAYER_INVENTORY', ?, 'PLAYER_INVENTORY', ?, ?, ?)
                        """)) {
                    provenance.setObject(1, item.itemInstanceId());
                    provenance.setObject(2, operationId);
                    provenance.setObject(3, owner);
                    provenance.setObject(4, owner);
                    provenance.setString(5, REASON);
                    provenance.setObject(6, owner);
                    assertEquals(1, provenance.executeUpdate());
                }
                assertThrows(SQLException.class, connection::commit);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    @Test
    void committedUpgradeEvidenceIsAppendOnly() throws Exception {
        UUID owner = player("UpgradeDbHistory");
        UniqueItemAuthorityResult item = createItem(owner);
        insertValidUpgrade(item.itemInstanceId(), owner);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE item_upgrade_events
                     SET reason = 'test.rewrite'
                     WHERE item_instance_id = ?
                     """)) {
            update.setObject(1, item.itemInstanceId());
            assertThrows(SQLException.class, update::executeUpdate);
        }
    }

    private void insertValidUpgrade(UUID itemId, UUID owner) throws SQLException {
        UUID operationId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE item_instances
                        SET upgrade_level = 1,
                            state_version = 1,
                            updated_at = NOW()
                        WHERE item_instance_id = ?
                          AND state_version = 0
                          AND upgrade_level = 0
                          AND location_kind = 'PLAYER_INVENTORY'
                          AND location_id = ?
                        """)) {
                    update.setObject(1, itemId);
                    update.setObject(2, owner);
                    assertEquals(1, update.executeUpdate());
                }
                try (PreparedStatement event = connection.prepareStatement("""
                        INSERT INTO item_upgrade_events(
                            item_instance_id,
                            operation_id,
                            from_state_version,
                            to_state_version,
                            from_upgrade_level,
                            to_upgrade_level,
                            reason,
                            actor_player_id
                        ) VALUES (?, ?, 0, 1, 0, 1, ?, ?)
                        """)) {
                    event.setObject(1, itemId);
                    event.setObject(2, operationId);
                    event.setString(3, REASON);
                    event.setObject(4, owner);
                    assertEquals(1, event.executeUpdate());
                }
                try (PreparedStatement provenance = connection.prepareStatement("""
                        INSERT INTO item_provenance(
                            item_instance_id,
                            sequence_no,
                            operation_id,
                            event_type,
                            from_location_kind,
                            from_location_id,
                            to_location_kind,
                            to_location_id,
                            reason,
                            actor_player_id
                        ) VALUES (?, 1, ?, 'UPGRADED', 'PLAYER_INVENTORY', ?, 'PLAYER_INVENTORY', ?, ?, ?)
                        """)) {
                    provenance.setObject(1, itemId);
                    provenance.setObject(2, operationId);
                    provenance.setObject(3, owner);
                    provenance.setObject(4, owner);
                    provenance.setString(5, REASON);
                    provenance.setObject(6, owner);
                    assertEquals(1, provenance.executeUpdate());
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private UniqueItemAuthorityResult createItem(UUID owner) throws SQLException {
        return items.createForPlayer(UUID.randomUUID(), SWORD, owner, "test.create", owner);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
