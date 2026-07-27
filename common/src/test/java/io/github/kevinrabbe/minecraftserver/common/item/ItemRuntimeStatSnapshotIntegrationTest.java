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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ItemRuntimeStatSnapshotIntegrationTest {
    private static final String SWORD = "equipment.runtime_sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ItemRepresentationAuthorityValidator validator;

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
                        SWORD,
                        "IRON_SWORD",
                        "Runtime Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL,
                        new ItemRollProfile(Map.of("damage", new RollRange(10_000, 12_000)))
                )
        ));
        validator = new ItemRepresentationAuthorityValidator(dataSource, catalog);
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
    void validAuthorityStateProducesDerivedRuntimeSnapshotWithoutAnotherGameplayQuery() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "RuntimeOwner");
        UUID itemId = insertItem(playerId, 4, "{\"damage\":5000}", 3);

        ItemRepresentationValidationResult result = validator.validateAndSnapshot(
                playerId,
                List.of(claim(itemId, 4))
        );

        assertTrue(result.valid());
        assertEquals(1, result.validatedIndividualSnapshots().size());
        ItemRuntimeStatSnapshot snapshot = result.validatedIndividualSnapshots().get(itemId);
        assertEquals(SWORD, snapshot.definitionId());
        assertEquals(ItemLocation.playerInventory(playerId), snapshot.location());
        assertEquals(4, snapshot.stateVersion());
        assertEquals(Map.of("damage", 5_000), snapshot.normalizedRollQualityBasisPoints());
        assertEquals(Map.of("damage", 11_000), snapshot.intrinsicMultipliersBasisPoints());
        assertEquals(new UpgradeState(3), snapshot.upgradeState());
    }

    @Test
    void persistedRollShapeThatNoLongerMatchesDefinitionFailsClosed() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "RuntimeMismatch");
        UUID itemId = insertItem(playerId, 0, "{\"speed\":5000}", 0);

        ItemRepresentationValidationResult result = validator.validateAndSnapshot(
                playerId,
                List.of(claim(itemId, 0))
        );

        assertFalse(result.valid());
        assertEquals(
                List.of(ItemRepresentationIssueCode.AUTHORITY_STAT_STATE_INVALID),
                result.issues().stream().map(ItemRepresentationIssue::code).toList()
        );
        assertTrue(result.validatedIndividualSnapshots().isEmpty());
    }

    private UUID insertItem(UUID playerId, long stateVersion, String rollStateJson, int upgradeLevel)
            throws SQLException {
        UUID itemId = UUID.randomUUID();
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
                         created_reason,
                         roll_state,
                         upgrade_level
                     ) VALUES (?, ?, 'PLAYER_INVENTORY', ?, ?, ?, ?, 'test.runtime_snapshot', ?::jsonb, ?)
                     """)) {
            statement.setObject(1, itemId);
            statement.setString(2, SWORD);
            statement.setObject(3, playerId);
            statement.setLong(4, stateVersion);
            statement.setObject(5, playerId);
            statement.setObject(6, UUID.randomUUID());
            statement.setString(7, rollStateJson);
            statement.setInt(8, upgradeLevel);
            assertEquals(1, statement.executeUpdate());
        }
        return itemId;
    }

    private static ItemRepresentationClaim claim(UUID itemId, long authorityVersion) {
        return new ItemRepresentationClaim(
                "storage[0]",
                SWORD,
                "IRON_SWORD",
                1,
                itemId,
                authorityVersion
        );
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }
}
