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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ItemUpgradeRepositoryIntegrationTest {
    private static final String SWORD = "equipment.upgrade_test";
    private static final String REASON = "test.item_upgrade";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private UniqueItemAuthorityRepository itemAuthority;
    private ItemUpgradeRepository upgrades;

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
        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                SWORD,
                "IRON_SWORD",
                "Upgrade Test Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        itemAuthority = new UniqueItemAuthorityRepository(dataSource, catalog);
        upgrades = new ItemUpgradeRepository(dataSource, catalog);
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
    void oneLevelUpgradeIsReplaySafeAndPreservesCustodyAndRollState() throws Exception {
        UUID owner = player("UpgradeOwner");
        UniqueItemAuthorityResult created = createItem(owner);
        UUID operationId = UUID.randomUUID();

        ItemUpgradeResult first = upgrades.upgradeOneLevel(
                operationId,
                created.itemInstanceId(),
                0,
                ItemLocation.playerInventory(owner),
                0,
                REASON,
                owner
        );
        ItemUpgradeResult retry = upgrades.upgradeOneLevel(
                operationId,
                created.itemInstanceId(),
                0,
                ItemLocation.playerInventory(owner),
                0,
                REASON,
                owner
        );

        assertEquals(first, retry);
        assertEquals(0, first.fromUpgradeLevel());
        assertEquals(1, first.toUpgradeLevel());
        assertEquals(0, first.fromStateVersion());
        assertEquals(1, first.toStateVersion());
        assertEquals(ItemLocation.playerInventory(owner), first.location());
        assertHead(created.itemInstanceId(), owner, 1, 1, "{}");
        assertUpgradeEvidence(operationId, created.itemInstanceId(), owner, 0, 1, 0, 1);
        assertEquals(1L, count("item_upgrade_events"));
        assertEquals(2L, count("item_provenance"));
    }

    @Test
    void twoConcurrentUpgradesFromSameAuthorityHeadCanAdvanceOnlyOnce() throws Exception {
        UUID owner = player("UpgradeRace");
        UniqueItemAuthorityResult created = createItem(owner);

        int successes = 0;
        int staleRejections = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ItemUpgradeResult> a = executor.submit(() -> upgrades.upgradeOneLevel(
                    UUID.randomUUID(), created.itemInstanceId(), 0, ItemLocation.playerInventory(owner), 0, REASON, owner
            ));
            Future<ItemUpgradeResult> b = executor.submit(() -> upgrades.upgradeOneLevel(
                    UUID.randomUUID(), created.itemInstanceId(), 0, ItemLocation.playerInventory(owner), 0, REASON, owner
            ));

            for (Future<ItemUpgradeResult> future : List.of(a, b)) {
                try {
                    ItemUpgradeResult result = future.get();
                    assertEquals(1, result.toUpgradeLevel());
                    successes++;
                } catch (ExecutionException exception) {
                    assertTrue(exception.getCause() instanceof UniqueItemAuthorityException);
                    staleRejections++;
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1, staleRejections);
        assertHead(created.itemInstanceId(), owner, 1, 1, "{}");
        assertEquals(1L, count("item_upgrade_events"));
    }

    @Test
    void operationIdCannotBeReboundToDifferentUpgradeRequest() throws Exception {
        UUID owner = player("UpgradeBound");
        UniqueItemAuthorityResult firstItem = createItem(owner);
        UniqueItemAuthorityResult secondItem = createItem(owner);
        UUID operationId = UUID.randomUUID();

        upgrades.upgradeOneLevel(
                operationId,
                firstItem.itemInstanceId(),
                0,
                ItemLocation.playerInventory(owner),
                0,
                REASON,
                owner
        );

        assertThrows(UniqueItemAuthorityException.class, () -> upgrades.upgradeOneLevel(
                operationId,
                secondItem.itemInstanceId(),
                0,
                ItemLocation.playerInventory(owner),
                0,
                REASON,
                owner
        ));
    }

    @Test
    void upgradesRequirePlayerInventoryCustodyBeforeAnyDatabaseMutation() throws Exception {
        UUID owner = player("UpgradeCustody");
        UniqueItemAuthorityResult created = createItem(owner);

        assertThrows(IllegalArgumentException.class, () -> upgrades.upgradeOneLevel(
                UUID.randomUUID(),
                created.itemInstanceId(),
                0,
                ItemLocation.auctionEscrow(UUID.randomUUID()),
                0,
                REASON,
                owner
        ));
        assertHead(created.itemInstanceId(), owner, 0, 0, "{}");
        assertEquals(0L, count("item_upgrade_events"));
    }

    @Test
    void databaseRejectsNakedUpgradeEvenWhenAProvenanceHeadIsForged() throws Exception {
        UUID owner = player("UpgradeRaw");
        UniqueItemAuthorityResult created = createItem(owner);
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
                    update.setObject(1, created.itemInstanceId());
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
                    provenance.setObject(1, created.itemInstanceId());
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

        assertHead(created.itemInstanceId(), owner, 0, 0, "{}");
        assertEquals(0L, count("item_upgrade_events"));
    }

    @Test
    void upgradeEvidenceIsAppendOnly() throws Exception {
        UUID owner = player("UpgradeHistory");
        UniqueItemAuthorityResult created = createItem(owner);
        upgrades.upgradeOneLevel(
                UUID.randomUUID(), created.itemInstanceId(), 0, ItemLocation.playerInventory(owner), 0, REASON, owner
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE item_upgrade_events
                     SET reason = 'test.rewrite'
                     WHERE item_instance_id = ?
                     """)) {
            update.setObject(1, created.itemInstanceId());
            assertThrows(SQLException.class, update::executeUpdate);
        }
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private UniqueItemAuthorityResult createItem(UUID owner) throws SQLException {
        return itemAuthority.createForPlayer(UUID.randomUUID(), SWORD, owner, "test.create", owner);
    }

    private void assertHead(
            UUID itemId,
            UUID owner,
            long expectedStateVersion,
            int expectedUpgradeLevel,
            String expectedRollState
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version, upgrade_level, roll_state::TEXT AS roll_state
                     FROM item_instances
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals("PLAYER_INVENTORY", row.getString("location_kind"));
                assertEquals(owner, row.getObject("location_id", UUID.class));
                assertEquals(expectedStateVersion, row.getLong("state_version"));
                assertEquals(expectedUpgradeLevel, row.getInt("upgrade_level"));
                assertEquals(expectedRollState, row.getString("roll_state"));
            }
        }
    }

    private void assertUpgradeEvidence(
            UUID operationId,
            UUID itemId,
            UUID owner,
            long fromState,
            long toState,
            int fromLevel,
            int toLevel
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT event.from_state_version,
                            event.to_state_version,
                            event.from_upgrade_level,
                            event.to_upgrade_level,
                            event.reason,
                            event.actor_player_id,
                            provenance.event_type,
                            provenance.from_location_kind,
                            provenance.from_location_id,
                            provenance.to_location_kind,
                            provenance.to_location_id
                     FROM item_upgrade_events event
                     JOIN item_provenance provenance
                       ON provenance.item_instance_id = event.item_instance_id
                      AND provenance.operation_id = event.operation_id
                      AND provenance.sequence_no = event.to_state_version
                     WHERE event.operation_id = ?
                       AND event.item_instance_id = ?
                     """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(fromState, row.getLong("from_state_version"));
                assertEquals(toState, row.getLong("to_state_version"));
                assertEquals(fromLevel, row.getInt("from_upgrade_level"));
                assertEquals(toLevel, row.getInt("to_upgrade_level"));
                assertEquals(REASON, row.getString("reason"));
                assertEquals(owner, row.getObject("actor_player_id", UUID.class));
                assertEquals("UPGRADED", row.getString("event_type"));
                assertEquals("PLAYER_INVENTORY", row.getString("from_location_kind"));
                assertEquals(owner, row.getObject("from_location_id", UUID.class));
                assertEquals("PLAYER_INVENTORY", row.getString("to_location_kind"));
                assertEquals(owner, row.getObject("to_location_id", UUID.class));
            }
        }
    }

    private long count(String table) throws SQLException {
        if (!List.of("item_upgrade_events", "item_provenance").contains(table)) {
            throw new IllegalArgumentException("unsupported table " + table);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
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
