package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateSnapshot;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
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
class PlayerItemUpgradeRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String BACKEND = "paper-a";
    private static final String SWORD = "equipment.player_upgrade_test";
    private static final String REASON = "test.player_item_upgrade";
    private static final byte[] CURRENT_PAYLOAD = new byte[]{4, 1, 0};
    private static final byte[] NEXT_PAYLOAD = new byte[]{4, 1, 1};

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private UniqueItemAuthorityRepository itemAuthority;
    private ItemCatalog catalog;

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
        catalog = new ItemCatalog(List.of(new ItemDefinition(
                SWORD,
                "IRON_SWORD",
                "Player Upgrade Test Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        itemAuthority = new UniqueItemAuthorityRepository(dataSource, catalog);
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
    void upgradeAtomicallyCommitsPlayerPayloadItemHeadEvidenceAndReplay() throws Exception {
        PlayerContext player = playerWithState("AtomicUpgrade", CURRENT_PAYLOAD);
        UniqueItemAuthorityResult item = createItem(player.playerId());
        AtomicInteger validations = new AtomicInteger();
        PlayerItemUpgradeRepository upgrades = repository((
                playerId,
                itemId,
                definitionId,
                fromVersion,
                toVersion,
                fromLevel,
                toLevel,
                currentPayload,
                nextPayload
        ) -> {
            validations.incrementAndGet();
            assertEquals(player.playerId(), playerId);
            assertEquals(item.itemInstanceId(), itemId);
            assertEquals(SWORD, definitionId);
            assertEquals(0, fromVersion);
            assertEquals(1, toVersion);
            assertEquals(0, fromLevel);
            assertEquals(1, toLevel);
            assertArrayEquals(CURRENT_PAYLOAD, currentPayload);
            assertArrayEquals(NEXT_PAYLOAD, nextPayload);
        });
        UUID operationId = UUID.randomUUID();

        PlayerItemUpgradeResult first = upgrades.upgradeOneLevel(
                operationId,
                player.lease().sessionId(),
                BACKEND,
                player.lease().stateVersion(),
                item.itemInstanceId(),
                0,
                0,
                "city",
                "forge",
                NEXT_PAYLOAD,
                REASON
        );
        PlayerItemUpgradeResult retry = upgrades.upgradeOneLevel(
                operationId,
                player.lease().sessionId(),
                BACKEND,
                player.lease().stateVersion(),
                item.itemInstanceId(),
                0,
                0,
                "city",
                "forge",
                NEXT_PAYLOAD,
                REASON
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertEquals(player.playerId(), first.playerId());
        assertEquals(player.lease().stateVersion() + 1, first.playerStateVersion());
        assertEquals(1, first.itemUpgrade().toStateVersion());
        assertEquals(1, first.itemUpgrade().toUpgradeLevel());

        PlayerStateSnapshot state = states.load(player.playerId());
        assertEquals(first.playerStateVersion(), state.stateVersion());
        assertArrayEquals(NEXT_PAYLOAD, state.statePayload());
        assertHead(item.itemInstanceId(), player.playerId(), 1, 1);
        assertEquals(1L, count("item_upgrade_events"));
        assertEquals(2L, count("item_provenance"));
    }

    @Test
    void adapterRejectionRollsBackBothPlayerAndItemAuthority() throws Exception {
        PlayerContext player = playerWithState("RejectUpgrade", CURRENT_PAYLOAD);
        UniqueItemAuthorityResult item = createItem(player.playerId());
        PlayerItemUpgradeRepository upgrades = repository((
                playerId,
                itemId,
                definitionId,
                fromVersion,
                toVersion,
                fromLevel,
                toLevel,
                currentPayload,
                nextPayload
        ) -> {
            throw new IllegalArgumentException("serialized item transition is invalid");
        });

        assertThrows(IllegalArgumentException.class, () -> upgrades.upgradeOneLevel(
                UUID.randomUUID(),
                player.lease().sessionId(),
                BACKEND,
                player.lease().stateVersion(),
                item.itemInstanceId(),
                0,
                0,
                "city",
                "forge",
                NEXT_PAYLOAD,
                REASON
        ));

        PlayerStateSnapshot state = states.load(player.playerId());
        assertEquals(player.lease().stateVersion(), state.stateVersion());
        assertArrayEquals(CURRENT_PAYLOAD, state.statePayload());
        assertHead(item.itemInstanceId(), player.playerId(), 0, 0);
        assertEquals(0L, count("item_upgrade_events"));
    }

    @Test
    void twoConcurrentOperationsFromSameSessionAndItemHeadCanCommitOnlyOnce() throws Exception {
        PlayerContext player = playerWithState("RaceUpgrade", CURRENT_PAYLOAD);
        UniqueItemAuthorityResult item = createItem(player.playerId());
        PlayerItemUpgradeRepository upgrades = repository((
                playerId,
                itemId,
                definitionId,
                fromVersion,
                toVersion,
                fromLevel,
                toLevel,
                currentPayload,
                nextPayload
        ) -> { });

        int successes = 0;
        int staleRejections = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<PlayerItemUpgradeResult> a = executor.submit(() -> upgrades.upgradeOneLevel(
                    UUID.randomUUID(), player.lease().sessionId(), BACKEND, player.lease().stateVersion(),
                    item.itemInstanceId(), 0, 0, "city", "forge", NEXT_PAYLOAD, REASON
            ));
            Future<PlayerItemUpgradeResult> b = executor.submit(() -> upgrades.upgradeOneLevel(
                    UUID.randomUUID(), player.lease().sessionId(), BACKEND, player.lease().stateVersion(),
                    item.itemInstanceId(), 0, 0, "city", "forge", NEXT_PAYLOAD, REASON
            ));
            for (Future<PlayerItemUpgradeResult> future : List.of(a, b)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException exception) {
                    assertTrue(exception.getCause() instanceof SessionConflictException
                            || exception.getCause() instanceof UniqueItemAuthorityException);
                    staleRejections++;
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1, staleRejections);
        assertHead(item.itemInstanceId(), player.playerId(), 1, 1);
        assertEquals(1L, count("item_upgrade_events"));
        assertEquals(player.lease().stateVersion() + 1, states.load(player.playerId()).stateVersion());
    }

    @Test
    void sessionIdentityNotCallerInputDeterminesUpgradeOwnership() throws Exception {
        PlayerContext owner = playerWithState("UpgradeOwnerA", CURRENT_PAYLOAD);
        PlayerContext outsider = playerWithState("UpgradeOwnerB", CURRENT_PAYLOAD);
        UniqueItemAuthorityResult item = createItem(owner.playerId());
        AtomicInteger validations = new AtomicInteger();
        PlayerItemUpgradeRepository upgrades = repository((
                playerId,
                itemId,
                definitionId,
                fromVersion,
                toVersion,
                fromLevel,
                toLevel,
                currentPayload,
                nextPayload
        ) -> validations.incrementAndGet());

        assertThrows(UniqueItemAuthorityException.class, () -> upgrades.upgradeOneLevel(
                UUID.randomUUID(),
                outsider.lease().sessionId(),
                BACKEND,
                outsider.lease().stateVersion(),
                item.itemInstanceId(),
                0,
                0,
                "city",
                "forge",
                NEXT_PAYLOAD,
                REASON
        ));

        assertEquals(0, validations.get());
        assertHead(item.itemInstanceId(), owner.playerId(), 0, 0);
        assertArrayEquals(CURRENT_PAYLOAD, states.load(outsider.playerId()).statePayload());
    }

    @Test
    void operationIdCannotBeReboundToDifferentSerializedPayload() throws Exception {
        PlayerContext player = playerWithState("BoundUpgrade", CURRENT_PAYLOAD);
        UniqueItemAuthorityResult item = createItem(player.playerId());
        PlayerItemUpgradeRepository upgrades = repository((
                playerId,
                itemId,
                definitionId,
                fromVersion,
                toVersion,
                fromLevel,
                toLevel,
                currentPayload,
                nextPayload
        ) -> { });
        UUID operationId = UUID.randomUUID();

        upgrades.upgradeOneLevel(
                operationId,
                player.lease().sessionId(),
                BACKEND,
                player.lease().stateVersion(),
                item.itemInstanceId(),
                0,
                0,
                "city",
                "forge",
                NEXT_PAYLOAD,
                REASON
        );

        assertThrows(UniqueItemAuthorityException.class, () -> upgrades.upgradeOneLevel(
                operationId,
                player.lease().sessionId(),
                BACKEND,
                player.lease().stateVersion(),
                item.itemInstanceId(),
                0,
                0,
                "city",
                "forge",
                new byte[]{9, 9, 9},
                REASON
        ));
    }

    private PlayerItemUpgradeRepository repository(PlayerItemUpgradeStateValidator validator) {
        return new PlayerItemUpgradeRepository(dataSource, catalog, validator);
    }

    private PlayerContext playerWithState(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease opened = sessions.openSession(playerId, BACKEND, null, LEASE);
        long committedVersion = states.commit(
                opened.sessionId(), BACKEND, opened.stateVersion(), "city", "forge", payload
        );
        SessionLease refreshed = sessions.heartbeat(opened.sessionId(), BACKEND, LEASE);
        assertEquals(committedVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private UniqueItemAuthorityResult createItem(UUID owner) throws SQLException {
        return itemAuthority.createForPlayer(UUID.randomUUID(), SWORD, owner, "test.create", owner);
    }

    private void assertHead(UUID itemId, UUID owner, long stateVersion, int upgradeLevel) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version, upgrade_level
                     FROM item_instances
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals("PLAYER_INVENTORY", row.getString("location_kind"));
                assertEquals(owner, row.getObject("location_id", UUID.class));
                assertEquals(stateVersion, row.getLong("state_version"));
                assertEquals(upgradeLevel, row.getInt("upgrade_level"));
            }
        }
    }

    private long count(String table) throws SQLException {
        if (!List.of("item_upgrade_events", "item_provenance").contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerContext(UUID playerId, SessionLease lease) { }
}
