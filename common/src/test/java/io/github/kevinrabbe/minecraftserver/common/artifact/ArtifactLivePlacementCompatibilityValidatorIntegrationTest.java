package io.github.kevinrabbe.minecraftserver.common.artifact;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ArtifactLivePlacementCompatibilityValidatorIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private ArtifactRepository artifacts;

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
        artifacts = new ArtifactRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        player_attunement_state,
                        player_artifact_discoveries,
                        artifact_locations,
                        artifact_definitions,
                        processed_operations,
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
    void emptyAuthorityAcceptsAnyFutureConfiguredSet() {
        assertDoesNotThrow(() -> ArtifactLivePlacementCompatibilityValidator.validate(
                dataSource,
                Set.of(UUID.randomUUID())
        ));
    }

    @Test
    void enabledArtifactMustRemainConfigured() throws Exception {
        UUID artifactId = createArtifact(true);

        assertThrows(
                ArtifactException.class,
                () -> ArtifactLivePlacementCompatibilityValidator.validate(dataSource, Set.of())
        );
        assertDoesNotThrow(() -> ArtifactLivePlacementCompatibilityValidator.validate(
                dataSource,
                Set.of(artifactId)
        ));
    }

    @Test
    void disabledHistoricalArtifactMayBeOmitted() throws Exception {
        createArtifact(false);

        assertDoesNotThrow(() -> ArtifactLivePlacementCompatibilityValidator.validate(dataSource, Set.of()));
    }

    @Test
    void additionalConfiguredArtifactDoesNotRequireExistingAuthority() throws Exception {
        UUID enabled = createArtifact(true);

        assertDoesNotThrow(() -> ArtifactLivePlacementCompatibilityValidator.validate(
                dataSource,
                Set.of(enabled, UUID.randomUUID())
        ));
    }

    private UUID createArtifact(boolean enabled) throws SQLException {
        UUID artifactId = UUID.randomUUID();
        artifacts.createArtifact(
                UUID.randomUUID(),
                artifactId,
                2,
                1,
                enabled,
                "minecraft:overworld",
                "city",
                10,
                64,
                -3
        );
        return artifactId;
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
