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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class StoredPlayerItemLiveContentCompatibilityValidatorIntegrationTest {
    private static final String WHEAT = "material.wheat";
    private static final String IRON = "material.raw_iron";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;

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
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE players RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void storedCommodityRequiresStableDefinitionAndRepresentation() throws Exception {
        UUID playerId = storedPlayer("StoredCommodity", new byte[] { 1 });
        StoredPlayerItemClaimReader wheatClaims = payload -> List.of(new ItemRepresentationClaim(
                "storage[0]",
                WHEAT,
                "WHEAT",
                32,
                null,
                null
        ));

        assertDoesNotThrow(() -> validate(catalog(commodity(WHEAT, "WHEAT", 64)), wheatClaims));
        assertThrows(
                ItemCatalogException.class,
                () -> validate(catalog(commodity(IRON, "RAW_IRON", 64)), wheatClaims)
        );
        assertThrows(
                ItemCatalogException.class,
                () -> validate(catalog(commodity(WHEAT, "HAY_BLOCK", 64)), wheatClaims)
        );
        assertThrows(
                ItemCatalogException.class,
                () -> validate(catalog(commodity(WHEAT, "WHEAT", 16)), wheatClaims)
        );

        clearPayload(playerId);
        assertDoesNotThrow(() -> validate(catalog(commodity(IRON, "RAW_IRON", 64)), wheatClaims));
    }

    @Test
    void malformedStoredPayloadFailsClosed() throws Exception {
        storedPlayer("MalformedStoredInventory", new byte[] { 9 });

        assertThrows(
                ItemCatalogException.class,
                () -> validate(
                        catalog(commodity(WHEAT, "WHEAT", 64)),
                        payload -> { throw new IllegalArgumentException("malformed payload"); }
                )
        );
    }

    private void validate(ItemCatalog catalog, StoredPlayerItemClaimReader reader) throws SQLException {
        StoredPlayerItemLiveContentCompatibilityValidator.validate(dataSource, catalog, reader);
    }

    private UUID storedPlayer(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_state
                     SET state_payload = ?
                     WHERE player_id = ?
                     """)) {
            statement.setBytes(1, payload);
            statement.setObject(2, playerId);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("missing player_state row");
        }
        return playerId;
    }

    private void clearPayload(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_state
                     SET state_payload = NULL
                     WHERE player_id = ?
                     """)) {
            statement.setObject(1, playerId);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("missing player_state row");
        }
    }

    private static ItemCatalog catalog(ItemDefinition definition) {
        return new ItemCatalog(List.of(definition));
    }

    private static ItemDefinition commodity(String definitionId, String material, int maxStack) {
        return new ItemDefinition(
                definitionId,
                material,
                definitionId,
                maxStack,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        );
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
