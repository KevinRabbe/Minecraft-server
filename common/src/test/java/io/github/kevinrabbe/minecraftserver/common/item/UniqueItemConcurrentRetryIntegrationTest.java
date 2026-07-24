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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class UniqueItemConcurrentRetryIntegrationTest {
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
    void simultaneousCreateRetriesReturnSameInstanceAndCreateOnce() throws Exception {
        UUID owner = createPlayer("ConcurrentCreate");
        UUID operationId = UUID.randomUUID();

        Pair<UniqueItemAuthorityResult> results = race(
                () -> items.createForPlayer(
                        operationId,
                        "equipment.test_sword",
                        owner,
                        "test.concurrent_create",
                        owner
                ),
                () -> items.createForPlayer(
                        operationId,
                        "equipment.test_sword",
                        owner,
                        "test.concurrent_create",
                        owner
                )
        );

        assertEquals(results.first(), results.second());
        assertEquals(1, count("SELECT COUNT(*) FROM item_instances"));
        assertEquals(1, count("SELECT COUNT(*) FROM item_provenance"));
        assertEquals(1, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(1, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void simultaneousMoveRetriesReturnSameCommittedMoveAndMoveOnce() throws Exception {
        UUID ownerA = createPlayer("ConcurrentMoveA");
        UUID ownerB = createPlayer("ConcurrentMoveB");
        UniqueItemAuthorityResult created = items.createForPlayer(
                UUID.randomUUID(),
                "equipment.test_sword",
                ownerA,
                "test.create",
                ownerA
        );
        UUID operationId = UUID.randomUUID();

        Pair<UniqueItemAuthorityResult> results = race(
                () -> items.move(
                        operationId,
                        created.itemInstanceId(),
                        0,
                        ItemLocation.playerInventory(ownerA),
                        ItemLocation.playerInventory(ownerB),
                        "test.concurrent_move",
                        ownerA
                ),
                () -> items.move(
                        operationId,
                        created.itemInstanceId(),
                        0,
                        ItemLocation.playerInventory(ownerA),
                        ItemLocation.playerInventory(ownerB),
                        "test.concurrent_move",
                        ownerA
                )
        );

        assertEquals(results.first(), results.second());
        assertEquals(1, results.first().stateVersion());
        assertEquals(ItemLocation.playerInventory(ownerB), items.load(created.itemInstanceId()).location());
        assertEquals(2, count("SELECT COUNT(*) FROM item_provenance"));
        assertEquals(3, count("SELECT COUNT(*) FROM economic_ledger"));
        assertEquals(2, count("SELECT COUNT(*) FROM processed_operations"));
    }

    @Test
    void operationIdRetryMustMatchOriginalReasonActorAndExpectedAuthority() throws SQLException {
        UUID ownerA = createPlayer("BoundA");
        UUID ownerB = createPlayer("BoundB");
        UUID otherActor = createPlayer("OtherActor");
        UUID createOperation = UUID.randomUUID();

        UniqueItemAuthorityResult created = items.createForPlayer(
                createOperation,
                "equipment.test_sword",
                ownerA,
                "test.original_create",
                ownerA
        );

        assertThrows(
                UniqueItemAuthorityException.class,
                () -> items.createForPlayer(
                        createOperation,
                        "equipment.test_sword",
                        ownerA,
                        "test.changed_reason",
                        ownerA
                )
        );
        assertThrows(
                UniqueItemAuthorityException.class,
                () -> items.createForPlayer(
                        createOperation,
                        "equipment.test_sword",
                        ownerA,
                        "test.original_create",
                        otherActor
                )
        );

        UUID moveOperation = UUID.randomUUID();
        items.move(
                moveOperation,
                created.itemInstanceId(),
                0,
                ItemLocation.playerInventory(ownerA),
                ItemLocation.playerInventory(ownerB),
                "test.original_move",
                ownerA
        );

        assertThrows(
                UniqueItemAuthorityException.class,
                () -> items.move(
                        moveOperation,
                        created.itemInstanceId(),
                        1,
                        ItemLocation.playerInventory(ownerB),
                        ItemLocation.playerInventory(ownerB),
                        "test.original_move",
                        ownerA
                )
        );
        assertThrows(
                UniqueItemAuthorityException.class,
                () -> items.move(
                        moveOperation,
                        created.itemInstanceId(),
                        0,
                        ItemLocation.playerInventory(ownerA),
                        ItemLocation.playerInventory(ownerB),
                        "test.changed_reason",
                        ownerA
                )
        );
        assertThrows(
                UniqueItemAuthorityException.class,
                () -> items.move(
                        moveOperation,
                        created.itemInstanceId(),
                        0,
                        ItemLocation.playerInventory(ownerA),
                        ItemLocation.playerInventory(ownerB),
                        "test.original_move",
                        otherActor
                )
        );
    }

    private <T> Pair<T> race(ThrowingSupplier<T> firstSupplier, ThrowingSupplier<T> secondSupplier) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<T> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start concurrent operation");
                }
                return firstSupplier.get();
            });
            Future<T> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start concurrent operation");
                }
                return secondSupplier.get();
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return new Pair<>(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
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

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record Pair<T>(T first, T second) {
    }
}
