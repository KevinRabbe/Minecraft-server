package io.github.kevinrabbe.minecraftserver.common.artifact;

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
class AttunementLiveProfileCompatibilityValidatorIntegrationTest {
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
    void activeProfileIdMustRemainConfiguredButTuningMayChange() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "AttuneCompat");
        setActiveProfile(playerId, "arcane");

        AttunementProfileCatalog arcaneV1 = profiles(new AttunementProfileDefinition("arcane", "intelligence"));
        AttunementProfileCatalog arcaneRetuned = profiles(new AttunementProfileDefinition("arcane", "spell_power"));
        AttunementProfileCatalog replacementOnly = profiles(new AttunementProfileDefinition("ember", "strength"));

        assertDoesNotThrow(() -> AttunementLiveProfileCompatibilityValidator.validate(dataSource, arcaneV1));
        assertDoesNotThrow(() -> AttunementLiveProfileCompatibilityValidator.validate(dataSource, arcaneRetuned));
        assertThrows(
                AttunementException.class,
                () -> AttunementLiveProfileCompatibilityValidator.validate(dataSource, replacementOnly)
        );

        setActiveProfile(playerId, "ember");
        assertDoesNotThrow(() -> AttunementLiveProfileCompatibilityValidator.validate(dataSource, replacementOnly));
    }

    private static AttunementProfileCatalog profiles(AttunementProfileDefinition profile) {
        return new AttunementProfileCatalog(List.of(profile));
    }

    private void setActiveProfile(UUID playerId, String profileId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO player_attunement_state(player_id, active_profile_id)
                     VALUES (?, ?)
                     ON CONFLICT (player_id) DO UPDATE
                     SET active_profile_id = EXCLUDED.active_profile_id,
                         state_version = player_attunement_state.state_version + 1,
                         updated_at = NOW()
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, profileId);
            statement.executeUpdate();
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
