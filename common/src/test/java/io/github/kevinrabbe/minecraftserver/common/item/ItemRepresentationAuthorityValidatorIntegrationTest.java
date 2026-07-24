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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ItemRepresentationAuthorityValidatorIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private UniqueItemAuthorityRepository itemAuthority;
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
        itemAuthority = new UniqueItemAuthorityRepository(dataSource, catalog);
        validator = new ItemRepresentationAuthorityValidator(dataSource, catalog);
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
    void validCommodityAndOwnedIndividualPass() throws SQLException {
        UUID playerId = createPlayer("ValidOwner");
        UniqueItemAuthorityResult item = createSword(playerId);

        List<ItemRepresentationIssue> issues = validator.validate(playerId, List.of(
                commodity("storage[0]", 32),
                individual("storage[1]", item.itemInstanceId(), item.stateVersion())
        ));

        assertTrue(issues.isEmpty());
    }

    @Test
    void duplicateAndUnknownInstanceIdsFailClosed() throws SQLException {
        UUID playerId = createPlayer("DuplicateOwner");
        UniqueItemAuthorityResult item = createSword(playerId);
        UUID unknown = UUID.randomUUID();

        List<ItemRepresentationIssue> issues = validator.validate(playerId, List.of(
                individual("storage[0]", item.itemInstanceId(), 0),
                individual("armor[0]", item.itemInstanceId(), 0),
                individual("storage[2]", unknown, 0)
        ));

        assertEquals(
                Set.of(
                        ItemRepresentationIssueCode.DUPLICATE_INSTANCE_ID,
                        ItemRepresentationIssueCode.UNKNOWN_INSTANCE_ID
                ),
                codes(issues)
        );
    }

    @Test
    void representationOwnedByAnotherPlayerIsRejected() throws SQLException {
        UUID owner = createPlayer("RealOwner");
        UUID claimant = createPlayer("Claimant");
        UniqueItemAuthorityResult item = createSword(owner);

        List<ItemRepresentationIssue> issues = validator.validate(
                claimant,
                List.of(individual("storage[0]", item.itemInstanceId(), item.stateVersion()))
        );

        assertEquals(Set.of(ItemRepresentationIssueCode.AUTHORITY_LOCATION_MISMATCH), codes(issues));
    }

    @Test
    void staleRepresentationVersionIsRejectedAfterAuthorityMoves() throws SQLException {
        UUID ownerA = createPlayer("VersionA");
        UUID ownerB = createPlayer("VersionB");
        UniqueItemAuthorityResult created = createSword(ownerA);
        UniqueItemAuthorityResult moved = itemAuthority.move(
                UUID.randomUUID(),
                created.itemInstanceId(),
                0,
                ItemLocation.playerInventory(ownerA),
                ItemLocation.playerInventory(ownerB),
                "test.move",
                ownerA
        );

        List<ItemRepresentationIssue> issues = validator.validate(
                ownerB,
                List.of(individual("storage[0]", moved.itemInstanceId(), 0))
        );

        assertEquals(Set.of(ItemRepresentationIssueCode.AUTHORITY_VERSION_MISMATCH), codes(issues));
    }

    @Test
    void structuralCatalogMismatchesAreReportedWithoutTrustingMetadata() throws SQLException {
        UUID playerId = createPlayer("ShapeOwner");
        UniqueItemAuthorityResult item = createSword(playerId);

        List<ItemRepresentationIssue> issues = validator.validate(playerId, List.of(
                new ItemRepresentationClaim(
                        "storage[0]",
                        "material.test_ore",
                        "STONE",
                        65,
                        item.itemInstanceId(),
                        0L
                ),
                new ItemRepresentationClaim(
                        "storage[1]",
                        "equipment.test_sword",
                        "IRON_SWORD",
                        1,
                        null,
                        null
                ),
                new ItemRepresentationClaim(
                        "storage[2]",
                        "missing.definition",
                        "STONE",
                        1,
                        null,
                        null
                )
        ));

        assertEquals(
                Set.of(
                        ItemRepresentationIssueCode.MATERIAL_MISMATCH,
                        ItemRepresentationIssueCode.INVALID_STACK_SIZE,
                        ItemRepresentationIssueCode.IDENTITY_SHAPE_MISMATCH,
                        ItemRepresentationIssueCode.UNKNOWN_DEFINITION
                ),
                codes(issues)
        );
    }

    @Test
    void databaseDefinitionMismatchIsRejectedEvenWhenMetadataUsesKnownDefinition() throws SQLException {
        UUID playerId = createPlayer("DefinitionOwner");
        UniqueItemAuthorityResult item = createSword(playerId);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }

        // The authority row is deliberately corrupted at the type boundary while keeping its location/version intact.
        // There is no DB FK to a runtime content catalog, so representation validation must still catch this.
        try (Connection connection = dataSource.getConnection();
             var update = connection.prepareStatement("""
                     UPDATE item_instances
                     SET definition_id = 'equipment.other_sword'
                     WHERE item_instance_id = ?
                     """)) {
            update.setObject(1, item.itemInstanceId());
            update.executeUpdate();
        }

        List<ItemRepresentationIssue> issues = validator.validate(
                playerId,
                List.of(individual("storage[0]", item.itemInstanceId(), 0))
        );

        assertEquals(Set.of(ItemRepresentationIssueCode.INSTANCE_DEFINITION_MISMATCH), codes(issues));
    }

    private UniqueItemAuthorityResult createSword(UUID ownerPlayerId) throws SQLException {
        return itemAuthority.createForPlayer(
                UUID.randomUUID(),
                "equipment.test_sword",
                ownerPlayerId,
                "test.create",
                ownerPlayerId
        );
    }

    private static ItemRepresentationClaim commodity(String source, int amount) {
        return new ItemRepresentationClaim(
                source,
                "material.test_ore",
                "RAW_IRON",
                amount,
                null,
                null
        );
    }

    private static ItemRepresentationClaim individual(String source, UUID itemInstanceId, long authorityVersion) {
        return new ItemRepresentationClaim(
                source,
                "equipment.test_sword",
                "IRON_SWORD",
                1,
                itemInstanceId,
                authorityVersion
        );
    }

    private UUID createPlayer(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private static Set<ItemRepresentationIssueCode> codes(List<ItemRepresentationIssue> issues) {
        return issues.stream().map(ItemRepresentationIssue::code).collect(Collectors.toSet());
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }
}
