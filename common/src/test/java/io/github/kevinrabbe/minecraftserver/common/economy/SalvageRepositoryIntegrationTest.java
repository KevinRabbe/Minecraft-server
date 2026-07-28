package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityResult;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SalvageRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String SWORD = "salvage.sword";
    private static final String SCRAP = "salvage.scrap";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private CoinWalletRepository wallets;
    private UniqueItemAuthorityRepository items;
    private SalvageCatalog salvageCatalog;

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
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);
        wallets = new CoinWalletRepository(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        SWORD, "IRON_SWORD", "Salvage Sword", 1,
                        ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL
                ),
                new ItemDefinition(
                        SCRAP, "IRON_NUGGET", "Salvage Scrap", 64,
                        ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY
                )
        ));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        salvageCatalog = new SalvageCatalog(
                List.of(new SalvageDefinition(SWORD, 250, Map.of(SCRAP, 3L))),
                catalog
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        salvage_records,
                        pending_commodity_deliveries,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
                        player_sessions,
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
    void salvageDestroysItemAndReturnsConfiguredValueExactlyOnce() throws Exception {
        PlayerContext player = playerWithSession("SalvageA", new byte[]{9, 8});
        UniqueItemAuthorityResult item = createSword(player.playerId());
        AtomicInteger validations = new AtomicInteger();
        SalvageRepository salvage = repository((playerId, itemId, current, next) -> {
            validations.incrementAndGet();
            assertEquals(player.playerId(), playerId);
            assertEquals(item.itemInstanceId(), itemId);
            assertArrayEquals(new byte[]{9, 8}, current);
            assertArrayEquals(new byte[]{8}, next);
        });
        UUID operationId = UUID.randomUUID();

        SalvageResult first = salvage.salvage(
                operationId,
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "salvage",
                new byte[]{8},
                "salvage.item"
        );
        SalvageResult retry = salvage.salvage(
                operationId,
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "salvage",
                new byte[]{8},
                "salvage.item"
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertEquals(250L, wallets.load(player.playerId()).balanceMinor());
        assertEquals(1, first.commodityReturns().size());
        assertEquals(SCRAP, first.commodityReturns().getFirst().commodityDefinitionId());
        assertEquals(3L, first.commodityReturns().getFirst().quantity());
        assertItemDestroyed(item.itemInstanceId(), item.stateVersion() + 1);
        assertPendingCommodity(first.commodityReturns().getFirst().deliveryId(), player.playerId(), 3);
        assertArrayEquals(new byte[]{8}, states.load(player.playerId()).statePayload());
        assertEquals(1L, tableCount("salvage_records"));
        assertEquals(1L, processedCount(operationId));
    }

    @Test
    void invalidSerializedRemovalRollsBackItemReturnsLedgerAndState() throws Exception {
        PlayerContext player = playerWithSession("SalvageBad", new byte[]{9});
        UniqueItemAuthorityResult item = createSword(player.playerId());
        SalvageRepository salvage = repository((playerId, itemId, current, next) -> {
            throw new SalvageException("invalid item removal");
        });

        assertThrows(SalvageException.class, () -> salvage.salvage(
                UUID.randomUUID(),
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "salvage",
                new byte[0],
                "salvage.item"
        ));

        assertItemLocation(item.itemInstanceId(), "PLAYER_INVENTORY", player.playerId(), item.stateVersion());
        assertEquals(0L, wallets.load(player.playerId()).balanceMinor());
        assertArrayEquals(new byte[]{9}, states.load(player.playerId()).statePayload());
        assertEquals(0L, tableCount("salvage_records"));
        assertEquals(0L, tableCount("pending_commodity_deliveries"));
    }

    @Test
    void twoDifferentOperationsCannotSalvageOneItemTwice() throws Exception {
        PlayerContext player = playerWithSession("SalvageRace", new byte[]{9});
        UniqueItemAuthorityResult item = createSword(player.playerId());
        SalvageRepository salvage = repository((playerId, itemId, current, next) -> { });

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SalvageResult> first = executor.submit(() -> salvage.salvage(
                    UUID.randomUUID(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                    item.itemInstanceId(), item.stateVersion(), "city", "salvage", new byte[0], "salvage.item"
            ));
            Future<SalvageResult> second = executor.submit(() -> salvage.salvage(
                    UUID.randomUUID(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                    item.itemInstanceId(), item.stateVersion(), "city", "salvage", new byte[0], "salvage.item"
            ));
            for (Future<SalvageResult> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof RuntimeException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(250L, wallets.load(player.playerId()).balanceMinor());
        assertEquals(1L, tableCount("salvage_records"));
        assertEquals(1L, tableCount("pending_commodity_deliveries"));
        assertItemDestroyed(item.itemInstanceId(), item.stateVersion() + 1);
    }

    @Test
    void operationIdCannotBeReboundToDifferentPayload() throws Exception {
        PlayerContext player = playerWithSession("SalvageBind", new byte[]{9});
        UniqueItemAuthorityResult item = createSword(player.playerId());
        SalvageRepository salvage = repository((playerId, itemId, current, next) -> { });
        UUID operationId = UUID.randomUUID();
        salvage.salvage(
                operationId, player.session().sessionId(), "paper-a", player.session().stateVersion(),
                item.itemInstanceId(), item.stateVersion(), "city", "salvage", new byte[0], "salvage.item"
        );

        assertThrows(SalvageException.class, () -> salvage.salvage(
                operationId, player.session().sessionId(), "paper-a", player.session().stateVersion(),
                item.itemInstanceId(), item.stateVersion(), "city", "salvage", new byte[]{1}, "salvage.item"
        ));
    }

    @Test
    void databaseRejectsFakeSalvageRecordWithoutDestructionProvenance() throws Exception {
        PlayerContext player = playerWithSession("SalvageProof", new byte[]{9});
        UniqueItemAuthorityResult item = createSword(player.playerId());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO salvage_records(
                        salvage_id, operation_id, player_id, item_instance_id, item_definition_id,
                        destroyed_item_version, coin_return_minor, commodity_returns
                    ) VALUES (?, ?, ?, ?, ?, 1, 0, '{}'::jsonb)
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, UUID.randomUUID());
                statement.setObject(3, player.playerId());
                statement.setObject(4, item.itemInstanceId());
                statement.setString(5, SWORD);
                statement.executeUpdate();
            }
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }
        assertEquals(0L, tableCount("salvage_records"));
    }

    @Test
    void committedSalvageRecordIsAppendOnly() throws Exception {
        PlayerContext player = playerWithSession("SalvageAudit", new byte[]{9});
        UniqueItemAuthorityResult item = createSword(player.playerId());
        SalvageResult result = repository((playerId, itemId, current, next) -> { }).salvage(
                UUID.randomUUID(), player.session().sessionId(), "paper-a", player.session().stateVersion(),
                item.itemInstanceId(), item.stateVersion(), "city", "salvage", new byte[0], "salvage.item"
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE salvage_records SET coin_return_minor = coin_return_minor + 1 WHERE salvage_id = ?
                     """)) {
            statement.setObject(1, result.salvageId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private SalvageRepository repository(UniqueItemEscrowValidator validator) {
        return new SalvageRepository(dataSource, salvageCatalog, validator);
    }

    private UniqueItemAuthorityResult createSword(UUID playerId) throws SQLException {
        return items.createForPlayer(UUID.randomUUID(), SWORD, playerId, "test.item", playerId);
    }

    private PlayerContext playerWithSession(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long version = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "salvage", payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(version, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void assertItemDestroyed(UUID itemId, long version) throws SQLException {
        assertItemLocation(itemId, "DESTROYED", null, version);
    }

    private void assertItemLocation(UUID itemId, String kind, UUID locationId, long version) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version FROM item_instances WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(kind, row.getString(1));
                assertEquals(locationId, row.getObject(2, UUID.class));
                assertEquals(version, row.getLong(3));
            }
        }
    }

    private void assertPendingCommodity(UUID deliveryId, UUID playerId, long quantity) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id, commodity_definition_id, quantity, status
                     FROM pending_commodity_deliveries WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, deliveryId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(playerId, row.getObject(1, UUID.class));
                assertEquals(SCRAP, row.getString(2));
                assertEquals(quantity, row.getLong(3));
                assertEquals("PENDING", row.getString(4));
            }
        }
    }

    private long tableCount(String table) throws SQLException {
        if (!List.of("salvage_records", "pending_commodity_deliveries").contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private long processedCount(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM processed_operations WHERE operation_id = ?")) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
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

    private record PlayerContext(UUID playerId, SessionLease session) { }
}
