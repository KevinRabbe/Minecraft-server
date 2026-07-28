package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanCommodityStorageDepositResult;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanCommodityStorageWithdrawResult;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanStorageRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanUniqueStorageDepositResult;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityResult;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanStorageIntegrityVerifierIntegrationTest {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String COMMODITY = "integrity.clan_iron";
    private static final String UNIQUE = "integrity.clan_sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private ClanMembershipRepository memberships;
    private UniqueItemAuthorityRepository items;
    private ClanStorageRepository storage;
    private ClanStorageIntegrityVerifier verifier;

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
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);
        memberships = new ClanMembershipRepository(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        COMMODITY,
                        "IRON_INGOT",
                        "Integrity Clan Iron",
                        64,
                        ItemCategory.MATERIALS,
                        ItemIdentityKind.COMMODITY
                ),
                new ItemDefinition(
                        UNIQUE,
                        "IRON_SWORD",
                        "Integrity Clan Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                )
        ));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        storage = new ClanStorageRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, currentPayload, nextPayload) -> { },
                (playerId, itemId, currentPayload, nextPayload) -> { }
        );
        verifier = new ClanStorageIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        truncateAuthority();
    }

    @AfterEach
    void cleanDatabase() throws SQLException {
        truncateAuthority();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void realCommodityAndUniqueDepositWithdrawHistoryRemainsClean() throws Exception {
        PlayerContext leader = playerWithSession("StoreIntLead", new byte[]{20});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Storage Integrity", "SGI");

        storage.depositCommodity(
                UUID.randomUUID(), clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 8L, "city", "storage",
                new byte[]{12}, "clan.integrity_commodity_deposit"
        );
        SessionLease refreshed = sessions.heartbeat(leader.session().sessionId(), "paper-a", LEASE);
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, leader.playerId(), "test.clan_storage_item", leader.playerId()
        );
        ClanUniqueStorageDepositResult depositedItem = storage.depositUniqueItem(
                UUID.randomUUID(), clan.clanId(), refreshed.sessionId(), "paper-a",
                refreshed.stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "storage", new byte[]{11}, "clan.integrity_unique_deposit"
        );
        storage.withdrawCommodity(
                UUID.randomUUID(), clan.clanId(), leader.playerId(), COMMODITY, 3L,
                "clan.integrity_commodity_withdraw"
        );
        storage.withdrawUniqueItem(
                UUID.randomUUID(), clan.clanId(), leader.playerId(), item.itemInstanceId(),
                depositedItem.itemStateVersion(), "clan.integrity_unique_withdraw"
        );

        assertEquals(5L, storage.loadCommodity(clan.clanId(), COMMODITY).orElseThrow().quantity());
        assertTrue(storage.listUniqueItems(clan.clanId(), 10).isEmpty());
        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void malformedUniqueVersionIsReportedInsteadOfCrashingVerifier() throws Exception {
        PlayerContext leader = playerWithSession("StoreBadVer", new byte[]{1});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Storage Bad Version", "SBV");
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, leader.playerId(), "test.clan_storage_item", leader.playerId()
        );
        UUID operationId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO processed_operations(operation_id, operation_type, result)
                     VALUES (?, 'CLAN_STORAGE_UNIQUE_DEPOSIT', ?::jsonb)
                     """)) {
            statement.setObject(1, operationId);
            statement.setString(2, """
                    {
                      "request": {
                        "clan_id": "%s",
                        "session_id": "%s",
                        "backend_id": "paper-a",
                        "expected_player_state_version": "1",
                        "item_instance_id": "%s",
                        "expected_item_state_version": "not-a-number",
                        "payload_sha256": "deadbeef",
                        "reason": "test.malformed_storage"
                      },
                      "result": {
                        "clan_id": "%s",
                        "player_id": "%s",
                        "item_instance_id": "%s",
                        "item_state_version": 1,
                        "player_state_version": 2
                      }
                    }
                    """.formatted(
                    clan.clanId(), leader.session().sessionId(), item.itemInstanceId(),
                    clan.clanId(), leader.playerId(), item.itemInstanceId()
            ));
            assertEquals(1, statement.executeUpdate());
        }

        List<IntegrityIssue> issues = verifier.verify(1);
        assertEquals(1, issues.size());
        assertEquals("CLAN_STORAGE_OPERATION_EVIDENCE_MISMATCH", issues.getFirst().code());
        assertEquals(operationId.toString(), issues.getFirst().subjectId());
    }

    @Test
    void mutableCommodityVersionDriftIsHistoryMismatchOnly() throws Exception {
        PlayerContext leader = playerWithSession("StoreHistDrift", new byte[]{10});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Storage History", "SHI");
        storage.depositCommodity(
                UUID.randomUUID(), clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 5L, "city", "storage",
                new byte[]{5}, "clan.integrity_commodity_deposit"
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_commodity_balances
                     SET state_version = state_version + 1
                     WHERE clan_id = ? AND commodity_definition_id = ?
                     """)) {
            statement.setObject(1, clan.clanId());
            statement.setString(2, COMMODITY);
            assertEquals(1, statement.executeUpdate());
        }

        assertContainsOnly(
                "CLAN_STORAGE_COMMODITY_HISTORY_MISMATCH",
                clan.clanId() + ":" + COMMODITY
        );
    }

    @Test
    void commodityWithdrawalDeliveryDriftIsDetectedIndependently() throws Exception {
        PlayerContext leader = playerWithSession("StoreDelivery", new byte[]{10});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Storage Delivery", "SDL");
        storage.depositCommodity(
                UUID.randomUUID(), clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 7L, "city", "storage",
                new byte[]{3}, "clan.integrity_commodity_deposit"
        );
        UUID withdrawOperationId = UUID.randomUUID();
        ClanCommodityStorageWithdrawResult withdrawn = storage.withdrawCommodity(
                withdrawOperationId, clan.clanId(), leader.playerId(), COMMODITY, 2L,
                "clan.integrity_commodity_withdraw"
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE pending_commodity_deliveries
                     SET quantity = quantity + 1
                     WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, withdrawn.deliveryId());
            assertEquals(1, statement.executeUpdate());
        }

        assertContainsOnly("CLAN_STORAGE_WITHDRAWAL_DELIVERY_MISMATCH", withdrawOperationId.toString());
    }

    @Test
    void missingUniqueDepositProvenanceIsDetectedIndependently() throws Exception {
        PlayerContext leader = playerWithSession("StoreProv", new byte[]{2});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Storage Provenance", "SPV");
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, leader.playerId(), "test.clan_storage_item", leader.playerId()
        );
        UUID depositOperationId = UUID.randomUUID();
        storage.depositUniqueItem(
                depositOperationId, clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "storage", new byte[]{1}, "clan.integrity_unique_deposit"
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM item_provenance WHERE operation_id = ?
                     """)) {
            statement.setObject(1, depositOperationId);
            assertEquals(1, statement.executeUpdate());
        }

        assertContainsOnly("CLAN_STORAGE_UNIQUE_PROVENANCE_MISMATCH", depositOperationId.toString());
    }

    @Test
    void extraStorageLedgerLineIsDetectedIndependently() throws Exception {
        PlayerContext leader = playerWithSession("StoreLedger", new byte[]{4});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Storage Ledger", "SLG");
        UUID depositOperationId = UUID.randomUUID();
        storage.depositCommodity(
                depositOperationId, clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 3L, "city", "storage",
                new byte[]{1}, "clan.integrity_commodity_deposit"
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO economic_ledger(
                         operation_id, line_no, player_id, asset_type, asset_id,
                         amount, direction, reason, related_entity_id
                     ) VALUES (?, 2, ?, 'COMMODITY', ?, 1, 'CREDIT', 'test.extra_storage_line', ?)
                     """)) {
            statement.setObject(1, depositOperationId);
            statement.setObject(2, leader.playerId());
            statement.setString(3, COMMODITY);
            statement.setString(4, clan.clanId().toString());
            assertEquals(1, statement.executeUpdate());
        }

        assertContainsOnly("CLAN_STORAGE_LEDGER_EVIDENCE_MISMATCH", depositOperationId.toString());
    }

    private PlayerContext playerWithSession(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long stateVersion = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "storage", payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(stateVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void assertContainsOnly(String expectedCode, String expectedSubject) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals(expectedCode, issue.code());
        assertEquals(expectedSubject, issue.subjectId());
    }

    private void truncateAuthority() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        pending_unique_deliveries,
                        pending_commodity_deliveries,
                        clan_invitations,
                        clan_commodity_balances,
                        clan_treasuries,
                        clan_members,
                        clans,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
                        player_sessions,
                        player_state,
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerContext(UUID playerId, SessionLease session) {
    }
}
