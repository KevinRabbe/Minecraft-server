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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class UniqueItemAuthorityRepositoryIntegrationTest {
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

        ItemCatalog catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        "equipment.test_sword",
                        "IRON_SWORD",
                        "Test Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                ),
                new ItemDefinition(
                        "material.test_ore",
                        "RAW_IRON",
                        "Test Ore",
                        64,
                        ItemCategory.MATERIALS,
                        ItemIdentityKind.COMMODITY
                )
        ));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
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
    void commodityDefinitionsCannotReceiveIndividualIdentity() throws SQLException {
        UUID owner = createPlayer("CommodityOwner");

        assertThrows(
                UniqueItemAuthorityException.class,
                () -> items.createForPlayer(
                        UUID.randomUUID(),
                        "material.test_ore",
                        owner,
                        "test.create",
                        owner
                )
        );

        assertEquals(0, count("SELECT COUNT(*) FROM item_instances"));
    }

    @Test
    void createIsIdempotentAndCreatesOneAuthorityRowProvenanceAndLedgerEntry() throws SQLException {
        UUID owner = createPlayer("Creator");
        UUID operationId = UUID.randomUUID();

        UniqueItemAuthorityResult first = items.createForPlayer(
                operationId,
                "equipment.test_sword",
                owner,
                "test.create",
                owner
        );
        UniqueItemAuthorityResult retry = items.createForPlayer(
                operationId,
                "equipment.test_sword",
                owner,
                "test.create",
                owner
        );

        assertEquals(first, retry);
        assertEquals(0, first.stateVersion());
        assertEquals(ItemLocation.playerInventory(owner), first.location());

        UniqueItemInstance loaded = items.load(first.itemInstanceId());
        assertEquals(first.itemInstanceId(), loaded.itemInstanceId());
        assertEquals("equipment.test_sword", loaded.definitionId());
        assertEquals(owner, loaded.originalOwnerPlayerId());
        assertEquals(ItemLocation.playerInventory(owner), loaded.location());
        assertEquals(0, loaded.stateVersion());

        assertEquals(1, count("SELECT COUNT(*) FROM item_instances"));
        assertEquals(1, count("SELECT COUNT(*) FROM item_provenance"));
        assertEquals(1, count("SELECT COUNT(*) FROM processed_operations"));
        assertEquals(1, count("SELECT COUNT(*) FROM economic_ledger"));
    }

    @Test
    void playerToPlayerMoveIsVersionFencedLedgeredAndIdempotent() throws SQLException {
        UUID ownerA = createPlayer("OwnerA");
        UUID ownerB = createPlayer("OwnerB");
        UniqueItemAuthorityResult created = items.createForPlayer(
                UUID.randomUUID(),
                "equipment.test_sword",
                ownerA,
                "test.create",
                ownerA
        );
        UUID moveOperation = UUID.randomUUID();

        UniqueItemAuthorityResult moved = items.move(
                moveOperation,
                created.itemInstanceId(),
                0,
                ItemLocation.playerInventory(ownerA),
                ItemLocation.playerInventory(ownerB),
                "test.trade",
                ownerA
        );
        UniqueItemAuthorityResult retry = items.move(
                moveOperation,
                created.itemInstanceId(),
                0,
                ItemLocation.playerInventory(ownerA),
                ItemLocation.playerInventory(ownerB),
                "test.trade",
                ownerA
        );

        assertEquals(moved, retry);
        assertEquals(1, moved.stateVersion());
        assertEquals(ItemLocation.playerInventory(ownerB), moved.location());
        assertEquals(ItemLocation.playerInventory(ownerB), items.load(created.itemInstanceId()).location());

        assertEquals(2, count("SELECT COUNT(*) FROM item_provenance"));
        assertEquals(2, count("SELECT COUNT(*) FROM processed_operations"));
        assertEquals(3, count("SELECT COUNT(*) FROM economic_ledger"));

        assertThrows(
                UniqueItemAuthorityException.class,
                () -> items.move(
                        UUID.randomUUID(),
                        created.itemInstanceId(),
                        0,
                        ItemLocation.playerInventory(ownerA),
                        ItemLocation.quarantine(),
                        "test.stale",
                        ownerA
                )
        );
        assertEquals(1, items.load(created.itemInstanceId()).stateVersion());
        assertEquals(ItemLocation.playerInventory(ownerB), items.load(created.itemInstanceId()).location());
    }

    @Test
    void quarantineRecoveryAndDestructionProduceOrderedProvenanceAndDestroyedIsTerminal() throws SQLException {
        UUID ownerA = createPlayer("OriginalOwner");
        UUID ownerB = createPlayer("RecoveredOwner");
        UniqueItemAuthorityResult created = items.createForPlayer(
                UUID.randomUUID(),
                "equipment.test_sword",
                ownerA,
                "test.create",
                ownerA
        );

        UniqueItemAuthorityResult quarantined = items.move(
                UUID.randomUUID(),
                created.itemInstanceId(),
                0,
                ItemLocation.playerInventory(ownerA),
                ItemLocation.quarantine(),
                "test.quarantine",
                null
        );
        UniqueItemAuthorityResult recovered = items.move(
                UUID.randomUUID(),
                created.itemInstanceId(),
                1,
                ItemLocation.quarantine(),
                ItemLocation.playerInventory(ownerB),
                "test.recover",
                null
        );
        UniqueItemAuthorityResult destroyed = items.move(
                UUID.randomUUID(),
                created.itemInstanceId(),
                2,
                ItemLocation.playerInventory(ownerB),
                ItemLocation.destroyed(),
                "test.destroy",
                null
        );

        assertEquals(1, quarantined.stateVersion());
        assertEquals(2, recovered.stateVersion());
        assertEquals(3, destroyed.stateVersion());
        assertEquals(ItemLocation.destroyed(), items.load(created.itemInstanceId()).location());
        assertEquals(
                List.of("CREATED", "QUARANTINED", "RECOVERED", "DESTROYED"),
                provenanceTypes(created.itemInstanceId())
        );

        assertThrows(
                UniqueItemAuthorityException.class,
                () -> items.move(
                        UUID.randomUUID(),
                        created.itemInstanceId(),
                        3,
                        ItemLocation.destroyed(),
                        ItemLocation.playerInventory(ownerA),
                        "test.illegal_restore",
                        null
                )
        );
    }

    @Test
    void reusingOperationIdForDifferentMoveFailsClosed() throws SQLException {
        UUID ownerA = createPlayer("ReuseA");
        UUID ownerB = createPlayer("ReuseB");
        UniqueItemAuthorityResult created = items.createForPlayer(
                UUID.randomUUID(),
                "equipment.test_sword",
                ownerA,
                "test.create",
                ownerA
        );
        UUID operationId = UUID.randomUUID();

        items.move(
                operationId,
                created.itemInstanceId(),
                0,
                ItemLocation.playerInventory(ownerA),
                ItemLocation.playerInventory(ownerB),
                "test.move",
                ownerA
        );

        assertThrows(
                UniqueItemAuthorityException.class,
                () -> items.move(
                        operationId,
                        created.itemInstanceId(),
                        0,
                        ItemLocation.playerInventory(ownerA),
                        ItemLocation.quarantine(),
                        "test.other_move",
                        ownerA
                )
        );
    }

    @Test
    void unknownPlayerCannotBecomeAuthoritativeInventoryOwner() throws SQLException {
        UUID owner = createPlayer("KnownOwner");
        UniqueItemAuthorityResult created = items.createForPlayer(
                UUID.randomUUID(),
                "equipment.test_sword",
                owner,
                "test.create",
                owner
        );
        UUID nonexistentPlayer = UUID.randomUUID();

        assertThrows(
                UniqueItemAuthorityException.class,
                () -> items.move(
                        UUID.randomUUID(),
                        created.itemInstanceId(),
                        0,
                        ItemLocation.playerInventory(owner),
                        ItemLocation.playerInventory(nonexistentPlayer),
                        "test.invalid_owner",
                        owner
                )
        );
        assertEquals(ItemLocation.playerInventory(owner), items.load(created.itemInstanceId()).location());
    }

    private UUID createPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            results.next();
            return results.getLong(1);
        }
    }

    private List<String> provenanceTypes(UUID itemInstanceId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT event_type
                     FROM item_provenance
                     WHERE item_instance_id = ?
                     ORDER BY sequence_no
                     """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet results = statement.executeQuery()) {
                List<String> events = new ArrayList<>();
                while (results.next()) {
                    events.add(results.getString("event_type"));
                }
                return events;
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
