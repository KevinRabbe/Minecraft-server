package io.github.kevinrabbe.minecraftserver.common.clan;

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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanStorageRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String COMMODITY = "clan.iron";
    private static final String UNIQUE = "clan.sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private ClanMembershipRepository memberships;
    private ClanRoleRepository roles;
    private UniqueItemAuthorityRepository items;
    private ItemCatalog catalog;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                10
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);
        memberships = new ClanMembershipRepository(dataSource);
        roles = new ClanRoleRepository(dataSource);
        catalog = new ItemCatalog(List.of(
                new ItemDefinition(COMMODITY, "IRON_INGOT", "Clan Iron", 64, ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY),
                new ItemDefinition(UNIQUE, "IRON_SWORD", "Clan Sword", 1, ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL)
        ));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
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

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void commodityDepositProvesExactRemovalAndExactRetryDoesNotChargeTwice() throws Exception {
        PlayerContext leader = playerWithSession("StoreLeadA", new byte[]{10});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Store Alpha", "STA");
        AtomicInteger validations = new AtomicInteger();
        ClanStorageRepository storage = new ClanStorageRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, currentPayload, nextPayload) -> {
                    validations.incrementAndGet();
                    assertEquals(leader.playerId(), playerId);
                    assertEquals(COMMODITY, definitionId);
                    assertEquals(3L, quantity);
                    assertArrayEquals(new byte[]{10}, currentPayload);
                    assertArrayEquals(new byte[]{7}, nextPayload);
                },
                (playerId, itemId, currentPayload, nextPayload) -> { }
        );
        UUID operationId = UUID.randomUUID();

        ClanCommodityStorageDepositResult first = storage.depositCommodity(
                operationId, clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 3, "city", "spawn", new byte[]{7},
                "clan.storage_deposit"
        );
        ClanCommodityStorageDepositResult retry = storage.depositCommodity(
                operationId, clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 3, "city", "spawn", new byte[]{7},
                "clan.storage_deposit"
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertEquals(3L, storage.loadCommodity(clan.clanId(), COMMODITY).orElseThrow().quantity());
        assertArrayEquals(new byte[]{7}, states.load(leader.playerId()).statePayload());
        assertEquals(-3L, playerAssetLedgerNet(leader.playerId(), "COMMODITY", COMMODITY));
        assertEquals(3L, clanAssetLedgerNet(clan.clanId(), "COMMODITY", COMMODITY));
    }

    @Test
    void rejectedCommodityRemovalRollsBackPlayerStateClanStorageAndLedger() throws Exception {
        PlayerContext leader = playerWithSession("StoreLeadB", new byte[]{10});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Store Beta", "STB");
        ClanStorageRepository storage = new ClanStorageRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, currentPayload, nextPayload) -> {
                    throw new ClanAssetException("invalid commodity removal");
                },
                (playerId, itemId, currentPayload, nextPayload) -> { }
        );

        assertThrows(ClanAssetException.class, () -> storage.depositCommodity(
                UUID.randomUUID(), clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 3, "city", "spawn", new byte[]{9},
                "clan.storage_deposit"
        ));

        assertFalse(storage.loadCommodity(clan.clanId(), COMMODITY).isPresent());
        assertArrayEquals(new byte[]{10}, states.load(leader.playerId()).statePayload());
        assertEquals(0L, clanAssetLedgerNet(clan.clanId(), "COMMODITY", COMMODITY));
    }

    @Test
    void memberCannotWithdrawCommodityButOfficerCanAndRetryKeepsOneDelivery() throws Exception {
        PlayerContext leader = playerWithSession("StoreLeadC", new byte[]{10});
        PlayerContext member = playerWithSession("StoreMemC", new byte[]{5});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Store Gamma", "STG");
        join(clan, leader.playerId(), member.playerId());
        ClanStorageRepository storage = permissiveStorage();
        storage.depositCommodity(
                UUID.randomUUID(), clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 8, "city", "spawn", new byte[]{2},
                "clan.storage_deposit"
        );

        assertThrows(ClanAssetException.class, () -> storage.withdrawCommodity(
                UUID.randomUUID(), clan.clanId(), member.playerId(), COMMODITY, 3, "clan.storage_withdraw"
        ));

        roles.setMemberRole(UUID.randomUUID(), clan.clanId(), leader.playerId(), member.playerId(), ClanRole.OFFICER);
        UUID operationId = UUID.randomUUID();
        ClanCommodityStorageWithdrawResult first = storage.withdrawCommodity(
                operationId, clan.clanId(), member.playerId(), COMMODITY, 3, "clan.storage_withdraw"
        );
        ClanCommodityStorageWithdrawResult retry = storage.withdrawCommodity(
                operationId, clan.clanId(), member.playerId(), COMMODITY, 3, "clan.storage_withdraw"
        );

        assertEquals(first, retry);
        assertEquals(5L, first.storage().quantity());
        assertEquals(1L, pendingCommodityDeliveryCount(first.deliveryId(), member.playerId(), 3));
        assertEquals(3L, playerAssetLedgerNet(member.playerId(), "COMMODITY", COMMODITY));
        assertEquals(5L, clanAssetLedgerNet(clan.clanId(), "COMMODITY", COMMODITY));
    }

    @Test
    void concurrentCommodityWithdrawalsCannotOverdrawClanStorage() throws Exception {
        PlayerContext leader = playerWithSession("StoreLeadD", new byte[]{10});
        PlayerContext officer = playerWithSession("StoreOffD", new byte[]{0});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Store Delta", "STD");
        join(clan, leader.playerId(), officer.playerId());
        roles.setMemberRole(UUID.randomUUID(), clan.clanId(), leader.playerId(), officer.playerId(), ClanRole.OFFICER);
        ClanStorageRepository storage = permissiveStorage();
        storage.depositCommodity(
                UUID.randomUUID(), clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 10, "city", "spawn", new byte[]{0},
                "clan.storage_deposit"
        );

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ClanCommodityStorageWithdrawResult> first = executor.submit(() -> storage.withdrawCommodity(
                    UUID.randomUUID(), clan.clanId(), leader.playerId(), COMMODITY, 8, "clan.storage_withdraw"
            ));
            Future<ClanCommodityStorageWithdrawResult> second = executor.submit(() -> storage.withdrawCommodity(
                    UUID.randomUUID(), clan.clanId(), officer.playerId(), COMMODITY, 8, "clan.storage_withdraw"
            ));
            for (Future<ClanCommodityStorageWithdrawResult> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof ClanAssetException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(2L, storage.loadCommodity(clan.clanId(), COMMODITY).orElseThrow().quantity());
        assertEquals(1L, totalPendingCommodityDeliveries(COMMODITY));
    }

    @Test
    void uniqueDepositProvesRemovalAndMovesExactItemIntoClanCustody() throws Exception {
        PlayerContext leader = playerWithSession("StoreLeadE", new byte[]{1, 2});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Store Epsilon", "STE");
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, leader.playerId(), "test.item", leader.playerId()
        );
        AtomicInteger validations = new AtomicInteger();
        ClanStorageRepository storage = new ClanStorageRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, currentPayload, nextPayload) -> { },
                (playerId, itemId, currentPayload, nextPayload) -> {
                    validations.incrementAndGet();
                    assertEquals(leader.playerId(), playerId);
                    assertEquals(item.itemInstanceId(), itemId);
                    assertArrayEquals(new byte[]{1, 2}, currentPayload);
                    assertArrayEquals(new byte[]{1}, nextPayload);
                }
        );
        UUID operationId = UUID.randomUUID();

        ClanUniqueStorageDepositResult first = storage.depositUniqueItem(
                operationId, clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[]{1}, "clan.storage_unique_deposit"
        );
        ClanUniqueStorageDepositResult retry = storage.depositUniqueItem(
                operationId, clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[]{1}, "clan.storage_unique_deposit"
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertItemCustody(item.itemInstanceId(), "CLAN_STORAGE", clan.clanId(), item.stateVersion() + 1);
        assertArrayEquals(new byte[]{1}, states.load(leader.playerId()).statePayload());
        assertEquals(1, storage.listUniqueItems(clan.clanId(), 10).size());
        // Creation credited the player once and storage deposit debited the same item once: net personal ownership is 0.
        assertEquals(0L, playerAssetLedgerNet(leader.playerId(), "ITEM_INSTANCE", item.itemInstanceId().toString()));
        assertEquals(1L, clanAssetLedgerNet(clan.clanId(), "ITEM_INSTANCE", item.itemInstanceId().toString()));
    }

    @Test
    void staleUniqueVersionFailsBeforePlayerStateMutation() throws Exception {
        PlayerContext leader = playerWithSession("StoreLeadF", new byte[]{1, 2});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Store Zeta", "STZ");
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, leader.playerId(), "test.item", leader.playerId()
        );
        ClanStorageRepository storage = permissiveStorage();

        assertThrows(ClanAssetException.class, () -> storage.depositUniqueItem(
                UUID.randomUUID(), clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), item.itemInstanceId(), item.stateVersion() + 1,
                "city", "spawn", new byte[]{1}, "clan.storage_unique_deposit"
        ));

        assertItemCustody(item.itemInstanceId(), "PLAYER_INVENTORY", leader.playerId(), item.stateVersion());
        assertArrayEquals(new byte[]{1, 2}, states.load(leader.playerId()).statePayload());
    }

    @Test
    void memberCannotWithdrawUniqueItemButOfficerCanIntoPendingDelivery() throws Exception {
        PlayerContext leader = playerWithSession("StoreLeadG", new byte[]{1, 2});
        PlayerContext member = playerWithSession("StoreMemG", new byte[]{3});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Store Eta", "SET");
        join(clan, leader.playerId(), member.playerId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, leader.playerId(), "test.item", leader.playerId()
        );
        ClanStorageRepository storage = permissiveStorage();
        ClanUniqueStorageDepositResult deposited = storage.depositUniqueItem(
                UUID.randomUUID(), clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[]{1}, "clan.storage_unique_deposit"
        );

        assertThrows(ClanAssetException.class, () -> storage.withdrawUniqueItem(
                UUID.randomUUID(), clan.clanId(), member.playerId(), item.itemInstanceId(),
                deposited.itemStateVersion(), "clan.storage_unique_withdraw"
        ));

        roles.setMemberRole(UUID.randomUUID(), clan.clanId(), leader.playerId(), member.playerId(), ClanRole.OFFICER);
        UUID operationId = UUID.randomUUID();
        ClanUniqueStorageWithdrawResult first = storage.withdrawUniqueItem(
                operationId, clan.clanId(), member.playerId(), item.itemInstanceId(),
                deposited.itemStateVersion(), "clan.storage_unique_withdraw"
        );
        ClanUniqueStorageWithdrawResult retry = storage.withdrawUniqueItem(
                operationId, clan.clanId(), member.playerId(), item.itemInstanceId(),
                deposited.itemStateVersion(), "clan.storage_unique_withdraw"
        );

        assertEquals(first, retry);
        assertItemCustody(item.itemInstanceId(), "PENDING_DELIVERY", first.deliveryId(), deposited.itemStateVersion() + 1);
        assertEquals(1L, pendingUniqueDeliveryCount(first.deliveryId(), member.playerId(), item.itemInstanceId()));
        assertEquals(1L, provenanceMoveCount(
                item.itemInstanceId(), "CLAN_STORAGE", clan.clanId(), "PENDING_DELIVERY", first.deliveryId()
        ));
        assertTrue(storage.listUniqueItems(clan.clanId(), 10).isEmpty());
        assertEquals(1L, playerAssetLedgerNet(member.playerId(), "ITEM_INSTANCE", item.itemInstanceId().toString()));
        assertEquals(0L, clanAssetLedgerNet(clan.clanId(), "ITEM_INSTANCE", item.itemInstanceId().toString()));
    }

    @Test
    void concurrentUniqueWithdrawalsCanProduceOnlyOneDelivery() throws Exception {
        PlayerContext leader = playerWithSession("StoreLeadH", new byte[]{1, 2});
        PlayerContext officer = playerWithSession("StoreOffH", new byte[]{3});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Store Theta", "STH");
        join(clan, leader.playerId(), officer.playerId());
        roles.setMemberRole(UUID.randomUUID(), clan.clanId(), leader.playerId(), officer.playerId(), ClanRole.OFFICER);
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), UNIQUE, leader.playerId(), "test.item", leader.playerId()
        );
        ClanStorageRepository storage = permissiveStorage();
        ClanUniqueStorageDepositResult deposited = storage.depositUniqueItem(
                UUID.randomUUID(), clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[]{1}, "clan.storage_unique_deposit"
        );

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ClanUniqueStorageWithdrawResult> first = executor.submit(() -> storage.withdrawUniqueItem(
                    UUID.randomUUID(), clan.clanId(), leader.playerId(), item.itemInstanceId(),
                    deposited.itemStateVersion(), "clan.storage_unique_withdraw"
            ));
            Future<ClanUniqueStorageWithdrawResult> second = executor.submit(() -> storage.withdrawUniqueItem(
                    UUID.randomUUID(), clan.clanId(), officer.playerId(), item.itemInstanceId(),
                    deposited.itemStateVersion(), "clan.storage_unique_withdraw"
            ));
            for (Future<ClanUniqueStorageWithdrawResult> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof ClanAssetException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1L, totalPendingUniqueDeliveries(item.itemInstanceId()));
    }

    @Test
    void operationIdCannotBeReboundToDifferentStorageRequest() throws Exception {
        PlayerContext leader = playerWithSession("StoreLeadI", new byte[]{10});
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader.playerId(), "Store Iota", "STI");
        ClanStorageRepository storage = permissiveStorage();
        UUID operationId = UUID.randomUUID();
        storage.depositCommodity(
                operationId, clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 2, "city", "spawn", new byte[]{8},
                "clan.storage_deposit"
        );

        assertThrows(ClanAssetException.class, () -> storage.depositCommodity(
                operationId, clan.clanId(), leader.session().sessionId(), "paper-a",
                leader.session().stateVersion(), COMMODITY, 3, "city", "spawn", new byte[]{7},
                "clan.storage_deposit"
        ));
        assertEquals(2L, storage.loadCommodity(clan.clanId(), COMMODITY).orElseThrow().quantity());
    }

    private ClanStorageRepository permissiveStorage() {
        return new ClanStorageRepository(
                dataSource,
                catalog,
                (playerId, definitionId, quantity, currentPayload, nextPayload) -> { },
                (playerId, itemId, currentPayload, nextPayload) -> { }
        );
    }

    private PlayerContext playerWithSession(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long stateVersion = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "spawn", payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(stateVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void join(ClanSnapshot clan, UUID leader, UUID player) throws SQLException {
        ClanInvitationSnapshot invite = memberships.invite(
                UUID.randomUUID(), clan.clanId(), leader, player, Instant.now().plus(1, ChronoUnit.DAYS)
        );
        memberships.acceptInvite(UUID.randomUUID(), invite.inviteId(), player);
    }

    private void assertItemCustody(UUID itemId, String kind, UUID locationId, long version) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version
                     FROM item_instances
                     WHERE item_instance_id = ?
                     """)) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(kind, row.getString("location_kind"));
                assertEquals(locationId, row.getObject("location_id", UUID.class));
                assertEquals(version, row.getLong("state_version"));
            }
        }
    }

    private long pendingCommodityDeliveryCount(UUID deliveryId, UUID playerId, long quantity) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM pending_commodity_deliveries
                     WHERE delivery_id = ? AND player_id = ? AND commodity_definition_id = ?
                       AND quantity = ? AND status = 'PENDING'
                     """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, playerId);
            statement.setString(3, COMMODITY);
            statement.setLong(4, quantity);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long totalPendingCommodityDeliveries(String commodity) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM pending_commodity_deliveries
                     WHERE commodity_definition_id = ? AND status = 'PENDING'
                     """)) {
            statement.setString(1, commodity);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long pendingUniqueDeliveryCount(UUID deliveryId, UUID playerId, UUID itemId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM pending_unique_deliveries
                     WHERE delivery_id = ? AND recipient_player_id = ? AND item_instance_id = ? AND status = 'PENDING'
                     """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, playerId);
            statement.setObject(3, itemId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long totalPendingUniqueDeliveries(UUID itemId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM pending_unique_deliveries
                     WHERE item_instance_id = ? AND status = 'PENDING'
                     """)) {
            statement.setObject(1, itemId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long provenanceMoveCount(UUID itemId, String fromKind, UUID fromId, String toKind, UUID toId)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM item_provenance
                     WHERE item_instance_id = ? AND from_location_kind = ? AND from_location_id = ?
                       AND to_location_kind = ? AND to_location_id = ?
                     """)) {
            statement.setObject(1, itemId);
            statement.setString(2, fromKind);
            statement.setObject(3, fromId);
            statement.setString(4, toKind);
            statement.setObject(5, toId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long playerAssetLedgerNet(UUID playerId, String assetType, String assetId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                     FROM economic_ledger
                     WHERE player_id = ? AND asset_type = ? AND asset_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, assetType);
            statement.setString(3, assetId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long clanAssetLedgerNet(UUID clanId, String assetType, String assetId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                     FROM economic_ledger
                     WHERE player_id IS NULL AND asset_type = ? AND asset_id = ? AND related_entity_id = ?
                     """)) {
            statement.setString(1, assetType);
            statement.setString(2, assetId);
            statement.setString(3, clanId.toString());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
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

    private record PlayerContext(UUID playerId, SessionLease session) { }
}
