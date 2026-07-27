package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemLocation;
import io.github.kevinrabbe.minecraftserver.common.item.ItemUpgradeRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityResult;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ItemUpgradeIntegrityVerifierIntegrationTest {
    private static final String SWORD = "verify.upgrade_sword";
    private static final String REASON = "verify.item_upgrade";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private UniqueItemAuthorityRepository items;
    private ItemUpgradeRepository upgrades;
    private ItemUpgradeIntegrityVerifier verifier;

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
        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                SWORD,
                "IRON_SWORD",
                "Upgrade Verifier Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        upgrades = new ItemUpgradeRepository(dataSource, catalog);
        verifier = new ItemUpgradeIntegrityVerifier(dataSource);
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
    void healthyUpgradeChainProducesNoUpgradeIntegrityIssues() throws Exception {
        UUID owner = player("VerifyUpHealthy");
        UniqueItemAuthorityResult item = createAndUpgrade(owner);

        assertTrue(verifier.verify(10).isEmpty());
        assertEquals(1, upgradeLevel(item.itemInstanceId()));
    }

    @Test
    void missingUpgradeEventForLiveItemIsReportedAndIncludedInAggregateVerifier() throws Exception {
        UUID owner = player("VerifyUpMissing");
        UniqueItemAuthorityResult item = createAndUpgrade(owner);
        deleteUpgradeEventAsCorruption(item.itemInstanceId());

        List<IntegrityIssue> issues = verifier.verify(10);

        assertEquals(1, issues.size());
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("ITEM_UPGRADE_CHAIN_MISMATCH", issue.code());
        assertEquals(item.itemInstanceId().toString(), issue.subjectId());

        assertTrue(new PersistentIntegrityVerifier(dataSource).verify(10_000).stream().anyMatch(
                aggregate -> aggregate.code().equals("ITEM_UPGRADE_CHAIN_MISMATCH")
                        && item.itemInstanceId().toString().equals(aggregate.subjectId())
        ));
    }

    @Test
    void rewrittenUpgradeProvenanceIsReportedWithoutMisclassifyingTheUpgradeChain() throws Exception {
        UUID owner = player("VerifyUpProv");
        UniqueItemAuthorityResult item = createAndUpgrade(owner);
        rewriteUpgradeProvenanceAsCorruption(item.itemInstanceId());

        List<IntegrityIssue> issues = verifier.verify(10);

        assertEquals(1, issues.size());
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("ITEM_UPGRADE_PROVENANCE_MISMATCH", issue.code());
        assertEquals(item.itemInstanceId().toString(), issue.subjectId());
    }

    @Test
    void orphanHistoricalUpgradeRowsWithoutCurrentItemAuthorityAreIgnored() throws Exception {
        UUID owner = player("VerifyUpOrphan");
        UniqueItemAuthorityResult item = createAndUpgrade(owner);
        removeCurrentItemAuthorityForFixtureCleanup(item.itemInstanceId());

        assertTrue(verifier.verify(10).isEmpty());
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private UniqueItemAuthorityResult createAndUpgrade(UUID owner) throws SQLException {
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), SWORD, owner, "verify.create", owner
        );
        upgrades.upgradeOneLevel(
                UUID.randomUUID(),
                item.itemInstanceId(),
                item.stateVersion(),
                ItemLocation.playerInventory(owner),
                0,
                REASON,
                owner
        );
        return item;
    }

    private void deleteUpgradeEventAsCorruption(UUID itemId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement control = connection.createStatement()) {
            control.execute("ALTER TABLE item_upgrade_events DISABLE TRIGGER item_upgrade_events_append_only");
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM item_upgrade_events WHERE item_instance_id = ?"
            )) {
                delete.setObject(1, itemId);
                assertEquals(1, delete.executeUpdate());
            } finally {
                control.execute("ALTER TABLE item_upgrade_events ENABLE TRIGGER item_upgrade_events_append_only");
            }
        }
    }

    private void rewriteUpgradeProvenanceAsCorruption(UUID itemId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement control = connection.createStatement()) {
            control.execute("ALTER TABLE item_provenance DISABLE TRIGGER item_provenance_append_only");
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE item_provenance
                    SET event_type = 'MOVED'
                    WHERE item_instance_id = ?
                      AND event_type = 'UPGRADED'
                    """)) {
                update.setObject(1, itemId);
                assertEquals(1, update.executeUpdate());
            } finally {
                control.execute("ALTER TABLE item_provenance ENABLE TRIGGER item_provenance_append_only");
            }
        }
    }

    private void removeCurrentItemAuthorityForFixtureCleanup(UUID itemId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement control = connection.createStatement()) {
            control.execute("ALTER TABLE item_provenance DISABLE TRIGGER item_provenance_append_only");
            try (PreparedStatement deleteProvenance = connection.prepareStatement(
                    "DELETE FROM item_provenance WHERE item_instance_id = ?"
            )) {
                deleteProvenance.setObject(1, itemId);
                deleteProvenance.executeUpdate();
            }
            control.execute("ALTER TABLE item_provenance ENABLE TRIGGER item_provenance_append_only");
            try (PreparedStatement deleteItem = connection.prepareStatement(
                    "DELETE FROM item_instances WHERE item_instance_id = ?"
            )) {
                deleteItem.setObject(1, itemId);
                assertEquals(1, deleteItem.executeUpdate());
            }
        }
    }

    private int upgradeLevel(UUID itemId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT upgrade_level FROM item_instances WHERE item_instance_id = ?"
             )) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getInt(1);
            }
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
