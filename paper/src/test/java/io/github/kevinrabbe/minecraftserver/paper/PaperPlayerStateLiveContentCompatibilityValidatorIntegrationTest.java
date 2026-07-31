package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PaperPlayerStateLiveContentCompatibilityValidatorIntegrationTest {
    private static final String COMMODITY = "material.compatibility_ingot";
    private static final String EQUIPMENT = "equipment.compatibility_sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
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
        catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        COMMODITY,
                        "IRON_INGOT",
                        "Compatibility Ingot",
                        64,
                        ItemCategory.MATERIALS,
                        ItemIdentityKind.COMMODITY
                ),
                new ItemDefinition(
                        EQUIPMENT,
                        "IRON_SWORD",
                        "Compatibility Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                )
        ));
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
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
    void nullPayloadDoesNotInvokeDecoder() throws Exception {
        identities.ensurePlayer(UUID.randomUUID(), "EmptyState");
        AtomicBoolean invoked = new AtomicBoolean();

        assertDoesNotThrow(() -> validator(payload -> {
            invoked.set(true);
            throw new AssertionError("Null payload must not be decoded");
        }).validate());
        assertFalse(invoked.get());
    }

    @Test
    void storedCommodityRequiresLoadedDefinitionAndRepresentationCompatibility() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "CommodityState");
        writePayload(playerId, 7L, new byte[]{1});

        assertDoesNotThrow(() -> validator(payload -> List.of(new ItemRepresentationClaim(
                "storage[0]",
                COMMODITY,
                "IRON_INGOT",
                32,
                null,
                null
        ))).validate());

        assertThrows(PaperItemRepresentationException.class, () -> validator(payload -> List.of(
                new ItemRepresentationClaim(
                        "storage[0]",
                        "material.removed",
                        "IRON_INGOT",
                        32,
                        null,
                        null
                )
        )).validate());
        assertThrows(PaperItemRepresentationException.class, () -> validator(payload -> List.of(
                new ItemRepresentationClaim(
                        "storage[0]",
                        COMMODITY,
                        "GOLD_INGOT",
                        32,
                        null,
                        null
                )
        )).validate());
        assertThrows(PaperItemRepresentationException.class, () -> validator(payload -> List.of(
                new ItemRepresentationClaim(
                        "storage[0]",
                        COMMODITY,
                        "IRON_INGOT",
                        65,
                        null,
                        null
                )
        )).validate());
    }

    @Test
    void storedIndividualItemRequiresExactAuthorityHead() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "IndividualState");
        UUID itemId = insertIndividualItem(playerId);
        writePayload(playerId, 11L, new byte[]{2});

        assertDoesNotThrow(() -> validator(payload -> List.of(new ItemRepresentationClaim(
                "storage[4]",
                EQUIPMENT,
                "IRON_SWORD",
                1,
                itemId,
                0L
        ))).validate());

        assertThrows(PaperItemRepresentationException.class, () -> validator(payload -> List.of(
                new ItemRepresentationClaim(
                        "storage[4]",
                        EQUIPMENT,
                        "IRON_SWORD",
                        1,
                        itemId,
                        1L
                )
        )).validate());
    }

    @Test
    void malformedStoredPayloadFailsClosed() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "MalformedState");
        writePayload(playerId, 13L, new byte[]{3});

        assertThrows(PaperItemRepresentationException.class, () -> validator(payload -> {
            throw new IllegalArgumentException("malformed test payload");
        }).validate());
    }

    private PaperPlayerStateLiveContentCompatibilityValidator validator(
            PaperPlayerStateLiveContentCompatibilityValidator.StoredClaimExtractor extractor
    ) {
        return new PaperPlayerStateLiveContentCompatibilityValidator(dataSource, catalog, extractor);
    }

    private void writePayload(UUID playerId, long stateVersion, byte[] payload) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_state
                     SET state_version = ?, state_payload = ?, updated_at = NOW()
                     WHERE player_id = ?
                     """)) {
            statement.setLong(1, stateVersion);
            statement.setBytes(2, payload);
            statement.setObject(3, playerId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Player state row is missing: " + playerId);
            }
        }
    }

    private UUID insertIndividualItem(UUID playerId) throws SQLException {
        UUID itemId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO item_instances(
                            item_instance_id,
                            definition_id,
                            location_kind,
                            location_id,
                            state_version,
                            original_owner_player_id,
                            created_by_operation_id,
                            created_reason,
                            roll_state,
                            upgrade_level
                        ) VALUES (?, ?, 'PLAYER_INVENTORY', ?, 0, ?, ?, 'test.stored_state', '{}'::jsonb, 0)
                        """)) {
                    statement.setObject(1, itemId);
                    statement.setString(2, EQUIPMENT);
                    statement.setObject(3, playerId);
                    statement.setObject(4, playerId);
                    statement.setObject(5, operationId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
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
                        ) VALUES (?, 0, ?, 'CREATED', NULL, NULL, 'PLAYER_INVENTORY', ?, 'test.stored_state', ?)
                        """)) {
                    statement.setObject(1, itemId);
                    statement.setObject(2, operationId);
                    statement.setObject(3, playerId);
                    statement.setObject(4, playerId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return itemId;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
