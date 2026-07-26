package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyFamilyId;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyPouchRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.bounty.BountyPouchWithdrawalResult;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapItemProfile;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PersistentPveIntegrityVerifierIntegrationTest {
    private static final String MAP = "verify.map";
    private static final String MATERIAL = "verify.zombie_essence";
    private static final String ERA = "founding";
    private static final BountyFamilyId FAMILY = new BountyFamilyId("zombie");
    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private MapAuthorityRepository maps;
    private BountyPouchRepository pouches;
    private PersistentPveIntegrityVerifier verifier;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                8
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        MAP,
                        "MAP",
                        "Verifier Map",
                        1,
                        ItemCategory.PROGRESSION,
                        ItemIdentityKind.INDIVIDUAL
                ),
                new ItemDefinition(
                        MATERIAL,
                        "FERMENTED_SPIDER_EYE",
                        "Verifier Essence",
                        64,
                        ItemCategory.MATERIALS,
                        ItemIdentityKind.COMMODITY
                )
        ));
        maps = new MapAuthorityRepository(dataSource, catalog, Clock.fixed(NOW, ZoneOffset.UTC));
        pouches = new BountyPouchRepository(dataSource, catalog);
        verifier = new PersistentPveIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        map_reward_grants,
                        map_reward_settlements,
                        map_clears,
                        map_run_participants,
                        map_runs,
                        map_item_profiles,
                        pending_commodity_deliveries,
                        bounty_pouch_balances,
                        bounty_pouches,
                        bounty_summons,
                        bounty_contracts,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
                        historical_events,
                        world_eras,
                        player_names,
                        player_state,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
        insertEra();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void healthyMapAndBountyEvidenceProducesNoIssues() throws Exception {
        UUID mapPlayer = player("VerifyMapHealthy");
        completeMap(mapPlayer);

        UUID bountyPlayer = player("VerifyBountyHealthy");
        insertCompletedBountyReward(bountyPlayer, 10);
        BountyPouchWithdrawalResult withdrawal = pouches.withdraw(
                UUID.randomUUID(),
                bountyPlayer,
                FAMILY,
                MATERIAL,
                4,
                "bounty.pouch_withdraw"
        );
        assertEquals(6L, withdrawal.balance().quantity());

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void reopenedConsumedMapItemIsReported() throws Exception {
        UUID player = player("VerifyMapCorrupt");
        MapItemProfile profile = maps.issueMap(
                UUID.randomUUID(), MAP, player, mapDefinition(), "map.issue"
        );
        UUID runId = maps.openMap(UUID.randomUUID(), profile.itemInstanceId(), player, 0, "map.open");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE item_instances
                     SET location_kind = 'PLAYER_INVENTORY',
                         location_id = ?,
                         state_version = state_version + 1,
                         updated_at = NOW()
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, player);
            statement.setObject(2, profile.itemInstanceId());
            assertEquals(1, statement.executeUpdate());
        }

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("MAP_OPEN_CONSUMPTION_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(runId.toString())));
    }

    @Test
    void clearAttachedToNonCompletedRunIsReported() throws Exception {
        UUID player = player("VerifyMapClear");
        MapItemProfile profile = maps.issueMap(
                UUID.randomUUID(), MAP, player, mapDefinition(), "map.issue"
        );
        UUID runId = maps.openMap(UUID.randomUUID(), profile.itemInstanceId(), player, 0, "map.open");
        maps.startRun(UUID.randomUUID(), runId, 0, List.of(player), "map.start");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO map_clears(
                         clear_id, run_id, difficulty, elapsed_millis, solo,
                         world_era_id, balance_version, completed_at
                     ) VALUES (?, ?, 1, 1000, TRUE, ?, 1, ?)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, runId);
            statement.setString(3, ERA);
            statement.setTimestamp(4, Timestamp.from(NOW.plusSeconds(10)));
            assertEquals(1, statement.executeUpdate());
        }

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("MAP_CLEAR_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(runId.toString())));
    }

    @Test
    void bountyPouchDriftIsReportedAgainstRewardAndWithdrawalHistory() throws Exception {
        UUID player = player("VerifyPouchDrift");
        insertCompletedBountyReward(player, 10);
        pouches.withdraw(UUID.randomUUID(), player, FAMILY, MATERIAL, 4, "bounty.pouch_withdraw");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE bounty_pouch_balances
                     SET quantity = quantity + 1,
                         state_version = state_version + 1,
                         updated_at = NOW()
                     WHERE player_id = ? AND family_id = ? AND commodity_definition_id = ?
                     """)) {
            statement.setObject(1, player);
            statement.setString(2, FAMILY.value());
            statement.setString(3, MATERIAL);
            assertEquals(1, statement.executeUpdate());
        }

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("BOUNTY_POUCH_CONSERVATION_MISMATCH")
                        && issue.subjectId().equals(player + ":" + FAMILY.value() + ":" + MATERIAL)));
    }

    @Test
    void bountyWithdrawalDeliveryDriftIsReported() throws Exception {
        UUID player = player("VerifyPouchDelivery");
        insertCompletedBountyReward(player, 10);
        BountyPouchWithdrawalResult withdrawal = pouches.withdraw(
                UUID.randomUUID(), player, FAMILY, MATERIAL, 4, "bounty.pouch_withdraw"
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE pending_commodity_deliveries
                     SET quantity = quantity + 1
                     WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, withdrawal.deliveryId());
            assertEquals(1, statement.executeUpdate());
        }

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("BOUNTY_WITHDRAWAL_DELIVERY_MISMATCH")));
    }

    @Test
    void outputIsBoundedAcrossPveCorruptions() throws Exception {
        UUID first = player("VerifyBoundMapA");
        UUID second = player("VerifyBoundMapB");
        corruptConsumedMap(first);
        corruptConsumedMap(second);

        assertEquals(1, verifier.verify(1).size());
    }

    private UUID completeMap(UUID player) throws SQLException {
        MapItemProfile profile = maps.issueMap(UUID.randomUUID(), MAP, player, mapDefinition(), "map.issue");
        UUID runId = maps.openMap(UUID.randomUUID(), profile.itemInstanceId(), player, 0, "map.open");
        maps.startRun(UUID.randomUUID(), runId, 0, List.of(player), "map.start");
        maps.completeRun(UUID.randomUUID(), runId, 1, 1_234, "map.complete");
        return runId;
    }

    private void corruptConsumedMap(UUID player) throws SQLException {
        MapItemProfile profile = maps.issueMap(UUID.randomUUID(), MAP, player, mapDefinition(), "map.issue");
        maps.openMap(UUID.randomUUID(), profile.itemInstanceId(), player, 0, "map.open");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE item_instances
                     SET state_version = state_version + 1,
                         updated_at = NOW()
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, profile.itemInstanceId());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertCompletedBountyReward(UUID player, long quantity) throws SQLException {
        UUID contractId = UUID.randomUUID();
        UUID rewardOperationId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement contract = connection.prepareStatement("""
                    INSERT INTO bounty_contracts(
                        contract_id, player_id, family_id, tier, status,
                        eligible_kill_progress, required_eligible_kills,
                        summon_authorizations_remaining, fee_operation_id,
                        reward_operation_id, state_version, completed_at
                    ) VALUES (?, ?, ?, 1, 'COMPLETED', 10, 10, 0, ?, ?, 4, ?)
                    """);
                 PreparedStatement pouch = connection.prepareStatement("""
                    INSERT INTO bounty_pouches(player_id, family_id)
                    VALUES (?, ?)
                    """);
                 PreparedStatement balance = connection.prepareStatement("""
                    INSERT INTO bounty_pouch_balances(
                        player_id, family_id, commodity_definition_id, quantity, state_version
                    ) VALUES (?, ?, ?, ?, 0)
                    """);
                 PreparedStatement processed = connection.prepareStatement("""
                    INSERT INTO processed_operations(operation_id, operation_type, result)
                    VALUES (?, 'BOUNTY_BOSS_COMPLETE', jsonb_build_object(
                        'contract', jsonb_build_object(
                            'contract_id', ?,
                            'player_id', ?,
                            'family_id', ?,
                            'tier', 1,
                            'status', 'COMPLETED',
                            'eligible_kill_progress', 10,
                            'required_eligible_kills', 10,
                            'summon_authorizations_remaining', 0,
                            'state_version', 4
                        ),
                        'pouch_rewards', jsonb_build_object(?, ?)
                    ))
                    """)) {
                contract.setObject(1, contractId);
                contract.setObject(2, player);
                contract.setString(3, FAMILY.value());
                contract.setObject(4, UUID.randomUUID());
                contract.setObject(5, rewardOperationId);
                contract.setTimestamp(6, Timestamp.from(NOW));
                contract.executeUpdate();

                pouch.setObject(1, player);
                pouch.setString(2, FAMILY.value());
                pouch.executeUpdate();

                balance.setObject(1, player);
                balance.setString(2, FAMILY.value());
                balance.setString(3, MATERIAL);
                balance.setLong(4, quantity);
                balance.executeUpdate();

                processed.setObject(1, rewardOperationId);
                processed.setString(2, contractId.toString());
                processed.setString(3, player.toString());
                processed.setString(4, FAMILY.value());
                processed.setString(5, MATERIAL);
                processed.setLong(6, quantity);
                processed.executeUpdate();

                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private MapRunDefinition mapDefinition() {
        return new MapRunDefinition(
                new MapDifficulty(1),
                "forest",
                "spider",
                "extermination",
                List.of(),
                42L,
                1,
                1,
                ERA
        );
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void insertEra() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO world_eras(era_id, sequence_no, started_at)
                     VALUES (?, 0, ?)
                     """)) {
            statement.setString(1, ERA);
            statement.setTimestamp(2, Timestamp.from(NOW.minusSeconds(3_600)));
            statement.executeUpdate();
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
