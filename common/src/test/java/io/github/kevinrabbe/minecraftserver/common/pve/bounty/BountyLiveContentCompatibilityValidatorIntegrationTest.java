package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BountyLiveContentCompatibilityValidatorIntegrationTest {
    private static final BountyFamilyId FAMILY = new BountyFamilyId("zombie");

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
            statement.execute("""
                    TRUNCATE TABLE
                        bounty_summons,
                        bounty_pouch_balances,
                        bounty_pouches,
                        bounty_contracts,
                        economic_ledger,
                        processed_operations,
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
    void liveHistoricalVersionMustRemainLoadedButTerminalHistoryMayBePruned() throws Exception {
        BountyTierDefinition v1 = tier(1, "boss.zombie.v1");
        BountyTierDefinition v2 = tier(2, "boss.zombie.v2");
        BountyTierCatalog v1Only = new BountyTierCatalog(List.of(v1));
        BountyTierCatalog v2Only = new BountyTierCatalog(List.of(v2));
        BountyTierCatalog both = new BountyTierCatalog(List.of(v1, v2));

        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "BountyCompatibility");
        BountyRepository repository = new BountyRepository(
                dataSource,
                v1Only,
                (contractId, definition) -> Map.of("material.zombie_essence", 1L),
                Duration.ofSeconds(30)
        );
        BountyContractStartResult started = repository.startContract(
                UUID.randomUUID(), playerId, FAMILY, 1, "bounty.start"
        );

        assertDoesNotThrow(() -> BountyLiveContentCompatibilityValidator.validate(dataSource, both));
        assertThrows(
                BountyException.class,
                () -> BountyLiveContentCompatibilityValidator.validate(dataSource, v2Only)
        );

        markCancelled(started.contract().contractId());
        assertDoesNotThrow(() -> BountyLiveContentCompatibilityValidator.validate(dataSource, v2Only));
    }

    private BountyTierDefinition tier(int version, String bossDefinitionId) {
        return new BountyTierDefinition(
                FAMILY,
                1,
                version,
                0L,
                1,
                bossDefinitionId,
                List.of("material.zombie_essence")
        );
    }

    private void markCancelled(UUID contractId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE bounty_contracts
                     SET status = 'CANCELLED', updated_at = NOW()
                     WHERE contract_id = ?
                     """)) {
            statement.setObject(1, contractId);
            if (statement.executeUpdate() != 1) {
                throw new AssertionError("expected one bounty contract to cancel");
            }
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
