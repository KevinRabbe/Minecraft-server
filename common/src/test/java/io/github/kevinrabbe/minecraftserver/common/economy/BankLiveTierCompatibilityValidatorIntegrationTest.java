package io.github.kevinrabbe.minecraftserver.common.economy;

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
class BankLiveTierCompatibilityValidatorIntegrationTest {
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
    void currentTierMustRemainConfiguredAndLargeEnoughForProtectedBalance() throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "BankCompat");
        setBankState(playerId, 2, 5_000_000L);

        BankTierCatalog full = new BankTierCatalog(List.of(
                new BankTierDefinition(0, 100_000L, 0L, 0),
                new BankTierDefinition(1, 1_000_000L, 50_000L, 10),
                new BankTierDefinition(2, 10_000_000L, 500_000L, 30)
        ));
        BankTierCatalog missingTierTwo = new BankTierCatalog(List.of(
                new BankTierDefinition(0, 100_000L, 0L, 0),
                new BankTierDefinition(1, 1_000_000L, 50_000L, 10)
        ));
        BankTierCatalog shrunkenTierTwo = new BankTierCatalog(List.of(
                new BankTierDefinition(0, 100_000L, 0L, 0),
                new BankTierDefinition(1, 1_000_000L, 50_000L, 10),
                new BankTierDefinition(2, 2_000_000L, 500_000L, 30)
        ));

        assertDoesNotThrow(() -> BankLiveTierCompatibilityValidator.validate(dataSource, full));
        assertThrows(
                BankManagerException.class,
                () -> BankLiveTierCompatibilityValidator.validate(dataSource, missingTierTwo)
        );
        assertThrows(
                BankManagerException.class,
                () -> BankLiveTierCompatibilityValidator.validate(dataSource, shrunkenTierTwo)
        );

        setBankState(playerId, 1, 750_000L);
        assertDoesNotThrow(() -> BankLiveTierCompatibilityValidator.validate(dataSource, missingTierTwo));
    }

    private void setBankState(UUID playerId, int tier, long balanceMinor) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE bank_accounts
                     SET tier = ?, balance_minor = ?, state_version = state_version + 1, updated_at = NOW()
                     WHERE player_id = ?
                     """)) {
            statement.setInt(1, tier);
            statement.setLong(2, balanceMinor);
            statement.setObject(3, playerId);
            if (statement.executeUpdate() != 1) throw new AssertionError("expected one bank account");
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
