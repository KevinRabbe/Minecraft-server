package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateSnapshot;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PlayerItemUpgradeEquipmentBoundaryIntegrationTest {
    private static final String BACKEND = "paper-a";
    private static final byte[] CURRENT_PAYLOAD = new byte[]{7, 0};
    private static final byte[] NEXT_PAYLOAD = new byte[]{7, 1};

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private UniqueItemAuthorityRepository items;
    private ItemCatalog catalog;

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
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);
        catalog = new ItemCatalog(List.of(new ItemDefinition(
                "map.upgrade_boundary",
                "MAP",
                "Upgrade Boundary Map",
                1,
                ItemCategory.PROGRESSION,
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
    void commonAuthorityRejectsNonEquipmentBeforePayloadValidatorOrStateMutation() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "MapUpBoundary");
        SessionLease opened = sessions.openSession(playerId, BACKEND, null, Duration.ofSeconds(30));
        long committed = states.commit(
                opened.sessionId(),
                BACKEND,
                opened.stateVersion(),
                "city",
                "portal",
                CURRENT_PAYLOAD
        );
        SessionLease live = sessions.heartbeat(opened.sessionId(), BACKEND, Duration.ofSeconds(30));
        assertEquals(committed, live.stateVersion());

        UniqueItemAuthorityResult map = items.createForPlayer(
                UUID.randomUUID(),
                "map.upgrade_boundary",
                playerId,
                "test.create_map",
                playerId
        );
        AtomicInteger validatorCalls = new AtomicInteger();
        PlayerItemUpgradeRepository upgrades = new PlayerItemUpgradeRepository(
                dataSource,
                catalog,
                (validatedPlayerId, itemInstanceId, definitionId, fromAuthorityVersion, toAuthorityVersion,
                 fromUpgradeLevel, toUpgradeLevel, currentPayload, nextPayload) -> validatorCalls.incrementAndGet()
        );

        UniqueItemAuthorityException failure = assertThrows(
                UniqueItemAuthorityException.class,
                () -> upgrades.upgradeOneLevel(
                        UUID.randomUUID(),
                        live.sessionId(),
                        BACKEND,
                        live.stateVersion(),
                        map.itemInstanceId(),
                        map.stateVersion(),
                        0,
                        "city",
                        "portal",
                        NEXT_PAYLOAD,
                        "test.map_upgrade"
                )
        );

        assertEquals("Only EQUIPMENT definitions can be upgraded: map.upgrade_boundary", failure.getMessage());
        assertEquals(0, validatorCalls.get());
        PlayerStateSnapshot state = states.load(playerId);
        assertEquals(live.stateVersion(), state.stateVersion());
        assertArrayEquals(CURRENT_PAYLOAD, state.statePayload());
        assertMapHeadUnchanged(map.itemInstanceId(), playerId);
        assertEquals(0L, count("item_upgrade_events"));
    }

    private void assertMapHeadUnchanged(UUID itemId, UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version, upgrade_level
                     FROM item_instances
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                assertEquals("PLAYER_INVENTORY", row.getString("location_kind"));
                assertEquals(playerId, row.getObject("location_id", UUID.class));
                assertEquals(0L, row.getLong("state_version"));
                assertEquals(0, row.getInt("upgrade_level"));
            }
        }
    }

    private long count(String table) throws SQLException {
        if (!"item_upgrade_events".equals(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM item_upgrade_events")) {
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
}
