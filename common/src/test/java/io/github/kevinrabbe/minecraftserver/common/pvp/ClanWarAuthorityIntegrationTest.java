package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanWarAuthorityIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String ITEM = "war.sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private ClanMembershipRepository memberships;
    private UniqueItemAuthorityRepository items;
    private ClanWarLifecycleRepository wars;
    private ClanWarResolutionRepository resolutions;
    private ItemCatalog catalog;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                12
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);
        memberships = new ClanMembershipRepository(dataSource);
        catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        ITEM,
                        "IRON_SWORD",
                        "War Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                )
        ));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        resolutions = new ClanWarResolutionRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        clan_war_results,
                        clan_war_items,
                        clan_war_rosters,
                        clan_wars,
                        clan_war_ratings,
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
    void challengeAcceptRosterLockAndStartAreExactAndRoleBound() throws Exception {
        WarFixture fixture = fixture("WarLifeA", "WarLifeB");
        UUID outsider = identities.ensurePlayer(UUID.randomUUID(), "WarOutsider");
        UUID challengeOperation = UUID.randomUUID();

        ClanWarSnapshot challenged = wars.challenge(
                challengeOperation,
                fixture.challenger().playerId(),
                fixture.challengerClan().clanId(),
                fixture.defenderClan().clanId()
        );
        assertEquals(challenged, wars.challenge(
                challengeOperation,
                fixture.challenger().playerId(),
                fixture.challengerClan().clanId(),
                fixture.defenderClan().clanId()
        ));
        assertEquals(ClanWarStatus.CHALLENGED, challenged.status());
        assertEquals(1000, resolutions.loadRating(fixture.challengerClan().clanId()).orElseThrow().rating());
        assertEquals(1000, resolutions.loadRating(fixture.defenderClan().clanId()).orElseThrow().rating());
        assertThrows(
                ClanWarException.class,
                () -> wars.accept(UUID.randomUUID(), challenged.warId(), outsider)
        );

        ClanWarSnapshot accepted = wars.accept(
                UUID.randomUUID(), challenged.warId(), fixture.defender().playerId()
        );
        assertEquals(ClanWarStatus.ACCEPTED, accepted.status());
        wars.setRoster(
                UUID.randomUUID(),
                accepted.warId(),
                fixture.challenger().playerId(),
                fixture.challengerClan().clanId(),
                List.of(fixture.challenger().playerId())
        );
        wars.setRoster(
                UUID.randomUUID(),
                accepted.warId(),
                fixture.defender().playerId(),
                fixture.defenderClan().clanId(),
                List.of(fixture.defender().playerId())
        );

        ClanWarSnapshot locked = wars.lockRoster(UUID.randomUUID(), accepted.warId());
        assertEquals(ClanWarStatus.ROSTER_LOCKED, locked.status());
        assertEquals(2, wars.loadRoster(accepted.warId()).size());
        ClanWarSnapshot active = wars.start(UUID.randomUUID(), accepted.warId());
        assertEquals(ClanWarStatus.ACTIVE, active.status());
        assertThrows(
                ClanWarException.class,
                () -> wars.setRoster(
                        UUID.randomUUID(), active.warId(), fixture.challenger().playerId(),
                        fixture.challengerClan().clanId(), List.of(fixture.challenger().playerId())
                )
        );
    }

    @Test
    void rosterLockRequiresExactBothSidesAndOnePlayerCannotOccupyTwoLiveWars() throws Exception {
        WarFixture first = fixture("WarRosterA", "WarRosterB");
        ClanWarSnapshot warA = acceptedWar(first);
        wars.setRoster(
                UUID.randomUUID(), warA.warId(), first.challenger().playerId(),
                first.challengerClan().clanId(), List.of(first.challenger().playerId())
        );
        assertThrows(SQLException.class, () -> rawLockRoster(warA.warId()));

        PlayerContext otherLeader = playerWithSession("WarRosterC", new byte[]{4});
        ClanSnapshot otherClan = memberships.createClan(
                UUID.randomUUID(), otherLeader.playerId(), "War Other", "WOC"
        );
        ClanWarSnapshot warB = wars.challenge(
                UUID.randomUUID(),
                otherLeader.playerId(),
                otherClan.clanId(),
                first.challengerClan().clanId()
        );
        wars.accept(UUID.randomUUID(), warB.warId(), first.challenger().playerId());
        assertThrows(
                SQLException.class,
                () -> wars.setRoster(
                        UUID.randomUUID(), warB.warId(), first.challenger().playerId(),
                        first.challengerClan().clanId(), List.of(first.challenger().playerId())
                )
        );
    }

    @Test
    void playerGearEntersWarCustodyOnlyAfterRosterLockAndSnapshotContainsPersistentIdentity() throws Exception {
        WarFixture fixture = fixture("WarGearA", "WarGearB");
        ClanWarSnapshot accepted = acceptedWar(fixture);
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), ITEM, fixture.challenger().playerId(), "test.item", fixture.challenger().playerId()
        );
        ClanWarLoadoutRepository loadouts = permissiveLoadouts();

        assertThrows(
                ClanWarException.class,
                () -> loadouts.depositPlayerItem(
                        UUID.randomUUID(), accepted.warId(), fixture.challenger().session().sessionId(), "paper-a",
                        fixture.challenger().session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                        "city", "spawn", new byte[]{0}, "clan.war_item_entry"
                )
        );

        setAndLockRosters(fixture, accepted.warId());
        AtomicInteger validations = new AtomicInteger();
        ClanWarLoadoutRepository provingLoadouts = new ClanWarLoadoutRepository(
                dataSource,
                catalog,
                (playerId, itemId, currentPayload, nextPayload) -> {
                    validations.incrementAndGet();
                    assertEquals(fixture.challenger().playerId(), playerId);
                    assertEquals(item.itemInstanceId(), itemId);
                    assertArrayEquals(new byte[]{1}, currentPayload);
                    assertArrayEquals(new byte[]{0}, nextPayload);
                }
        );
        UUID operationId = UUID.randomUUID();
        ClanWarCustodyDepositResult first = provingLoadouts.depositPlayerItem(
                operationId,
                accepted.warId(),
                fixture.challenger().session().sessionId(),
                "paper-a",
                fixture.challenger().session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "spawn",
                new byte[]{0},
                "clan.war_item_entry"
        );
        ClanWarCustodyDepositResult retry = provingLoadouts.depositPlayerItem(
                operationId,
                accepted.warId(),
                fixture.challenger().session().sessionId(),
                "paper-a",
                fixture.challenger().session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "spawn",
                new byte[]{0},
                "clan.war_item_entry"
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertEquals(item.itemInstanceId(), first.item().itemInstanceId());
        assertEquals(ITEM, first.item().definitionId());
        assertItemCustody(item.itemInstanceId(), "WAR_CUSTODY", accepted.warId(), item.stateVersion() + 1);
        List<ClanWarCustodiedItemSnapshot> snapshot = provingLoadouts.loadActiveCombatSnapshot(accepted.warId());
        assertEquals(1, snapshot.size());
        assertEquals(item.itemInstanceId(), snapshot.getFirst().itemInstanceId());
        assertArrayEquals(new byte[]{0}, states.load(fixture.challenger().playerId()).statePayload());
    }

    @Test
    void completedWarSettlesRatingsOnceAndReturnsAllGearThroughPendingDelivery() throws Exception {
        WarFixture fixture = fixture("WarWinA", "WarWinB");
        ClanWarSnapshot accepted = acceptedWar(fixture);
        setAndLockRosters(fixture, accepted.warId());
        UniqueItemAuthorityResult challengerItem = items.createForPlayer(
                UUID.randomUUID(), ITEM, fixture.challenger().playerId(), "test.item", fixture.challenger().playerId()
        );
        UniqueItemAuthorityResult defenderItem = items.createForPlayer(
                UUID.randomUUID(), ITEM, fixture.defender().playerId(), "test.item", fixture.defender().playerId()
        );
        ClanWarLoadoutRepository loadouts = permissiveLoadouts();
        ClanWarCustodyDepositResult challengerCustody = loadouts.depositPlayerItem(
                UUID.randomUUID(), accepted.warId(), fixture.challenger().session().sessionId(), "paper-a",
                fixture.challenger().session().stateVersion(), challengerItem.itemInstanceId(), challengerItem.stateVersion(),
                "city", "spawn", new byte[]{0}, "clan.war_item_entry"
        );
        ClanWarCustodyDepositResult defenderCustody = loadouts.depositPlayerItem(
                UUID.randomUUID(), accepted.warId(), fixture.defender().session().sessionId(), "paper-a",
                fixture.defender().session().stateVersion(), defenderItem.itemInstanceId(), defenderItem.stateVersion(),
                "city", "spawn", new byte[]{0}, "clan.war_item_entry"
        );
        wars.start(UUID.randomUUID(), accepted.warId());
        UUID operationId = UUID.randomUUID();

        ClanWarCompletionResult first = resolutions.complete(
                operationId, accepted.warId(), fixture.challengerClan().clanId()
        );
        ClanWarCompletionResult retry = resolutions.complete(
                operationId, accepted.warId(), fixture.challengerClan().clanId()
        );

        assertEquals(first, retry);
        assertEquals(ClanWarStatus.COMPLETED, first.war().status());
        assertEquals(1000, first.challengerBefore().rating());
        assertEquals(1016, first.challengerAfter().rating());
        assertEquals(1000, first.defenderBefore().rating());
        assertEquals(984, first.defenderAfter().rating());
        assertEquals(2, first.returnDeliveryIds().size());
        assertEquals(1L, warResultCount(accepted.warId()));
        assertEquals(0L, activeWarItemCount(accepted.warId()));
        assertEquals(0L, liveRosterCount(accepted.warId()));
        assertPendingReturn(challengerItem.itemInstanceId(), fixture.challenger().playerId(), challengerCustody.item().itemStateVersion() + 1);
        assertPendingReturn(defenderItem.itemInstanceId(), fixture.defender().playerId(), defenderCustody.item().itemStateVersion() + 1);
        assertThrows(SQLException.class, () -> mutateWarResult(accepted.warId()));
        assertThrows(ClanWarException.class, () -> resolutions.complete(
                UUID.randomUUID(), accepted.warId(), fixture.defenderClan().clanId()
        ));
    }

    @Test
    void cancellationAfterCustodyReturnsGearWithoutMovingRatingAndAllowsPlayerIntoNewWar() throws Exception {
        WarFixture fixture = fixture("WarCanA", "WarCanB");
        ClanWarSnapshot accepted = acceptedWar(fixture);
        setAndLockRosters(fixture, accepted.warId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), ITEM, fixture.challenger().playerId(), "test.item", fixture.challenger().playerId()
        );
        ClanWarCustodyDepositResult custody = permissiveLoadouts().depositPlayerItem(
                UUID.randomUUID(), accepted.warId(), fixture.challenger().session().sessionId(), "paper-a",
                fixture.challenger().session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[]{0}, "clan.war_item_entry"
        );
        UUID operationId = UUID.randomUUID();

        ClanWarTerminalResult first = resolutions.cancel(
                operationId, accepted.warId(), fixture.challenger().playerId()
        );
        ClanWarTerminalResult retry = resolutions.cancel(
                operationId, accepted.warId(), fixture.challenger().playerId()
        );

        assertEquals(first, retry);
        assertEquals(ClanWarStatus.CANCELLED, first.war().status());
        assertEquals(1000, resolutions.loadRating(fixture.challengerClan().clanId()).orElseThrow().rating());
        assertEquals(1000, resolutions.loadRating(fixture.defenderClan().clanId()).orElseThrow().rating());
        assertPendingReturn(item.itemInstanceId(), fixture.challenger().playerId(), custody.item().itemStateVersion() + 1);
        assertEquals(0L, liveRosterCount(accepted.warId()));

        PlayerContext other = playerWithSession("WarCanC", new byte[]{5});
        ClanSnapshot otherClan = memberships.createClan(UUID.randomUUID(), other.playerId(), "War Can C", "WCC");
        ClanWarSnapshot next = wars.challenge(
                UUID.randomUUID(), other.playerId(), otherClan.clanId(), fixture.challengerClan().clanId()
        );
        wars.accept(UUID.randomUUID(), next.warId(), fixture.challenger().playerId());
        wars.setRoster(
                UUID.randomUUID(), next.warId(), fixture.challenger().playerId(),
                fixture.challengerClan().clanId(), List.of(fixture.challenger().playerId())
        );
    }

    @Test
    void activeFailureReturnsGearWithoutRatingSettlement() throws Exception {
        WarFixture fixture = fixture("WarFailA", "WarFailB");
        ClanWarSnapshot accepted = acceptedWar(fixture);
        setAndLockRosters(fixture, accepted.warId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), ITEM, fixture.defender().playerId(), "test.item", fixture.defender().playerId()
        );
        ClanWarCustodyDepositResult custody = permissiveLoadouts().depositPlayerItem(
                UUID.randomUUID(), accepted.warId(), fixture.defender().session().sessionId(), "paper-a",
                fixture.defender().session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[]{0}, "clan.war_item_entry"
        );
        wars.start(UUID.randomUUID(), accepted.warId());

        ClanWarTerminalResult result = resolutions.fail(UUID.randomUUID(), accepted.warId());

        assertEquals(ClanWarStatus.FAILED, result.war().status());
        assertEquals(0L, warResultCount(accepted.warId()));
        assertEquals(1000, resolutions.loadRating(fixture.challengerClan().clanId()).orElseThrow().rating());
        assertEquals(1000, resolutions.loadRating(fixture.defenderClan().clanId()).orElseThrow().rating());
        assertPendingReturn(item.itemInstanceId(), fixture.defender().playerId(), custody.item().itemStateVersion() + 1);
    }

    @Test
    void concurrentCompletionCanSettleWarAndReturnGearOnlyOnce() throws Exception {
        WarFixture fixture = fixture("WarRaceA", "WarRaceB");
        ClanWarSnapshot accepted = acceptedWar(fixture);
        setAndLockRosters(fixture, accepted.warId());
        wars.start(UUID.randomUUID(), accepted.warId());

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ClanWarCompletionResult> first = executor.submit(() -> resolutions.complete(
                    UUID.randomUUID(), accepted.warId(), fixture.challengerClan().clanId()
            ));
            Future<ClanWarCompletionResult> second = executor.submit(() -> resolutions.complete(
                    UUID.randomUUID(), accepted.warId(), fixture.defenderClan().clanId()
            ));
            for (Future<ClanWarCompletionResult> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof ClanWarException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1L, warResultCount(accepted.warId()));
        assertEquals(2000,
                resolutions.loadRating(fixture.challengerClan().clanId()).orElseThrow().rating()
                        + resolutions.loadRating(fixture.defenderClan().clanId()).orElseThrow().rating());
    }

    @Test
    void databaseRejectsTerminalWarWithStrandedCustodyAndCompletedWarWithoutResult() throws Exception {
        WarFixture fixture = fixture("WarDbA", "WarDbB");
        ClanWarSnapshot accepted = acceptedWar(fixture);
        setAndLockRosters(fixture, accepted.warId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), ITEM, fixture.challenger().playerId(), "test.item", fixture.challenger().playerId()
        );
        permissiveLoadouts().depositPlayerItem(
                UUID.randomUUID(), accepted.warId(), fixture.challenger().session().sessionId(), "paper-a",
                fixture.challenger().session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[]{0}, "clan.war_item_entry"
        );
        wars.start(UUID.randomUUID(), accepted.warId());

        assertThrows(SQLException.class, () -> rawCompleteWithoutResult(accepted.warId(), fixture.challengerClan().clanId()));
        assertEquals(ClanWarStatus.ACTIVE, wars.loadWar(accepted.warId()).orElseThrow().status());
        assertItemCustody(item.itemInstanceId(), "WAR_CUSTODY", accepted.warId(), item.stateVersion() + 1);
    }

    private WarFixture fixture(String challengerName, String defenderName) throws SQLException {
        PlayerContext challenger = playerWithSession(challengerName, new byte[]{1});
        PlayerContext defender = playerWithSession(defenderName, new byte[]{1});
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), challengerName + " C", uniqueTag(challengerName)
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), defenderName + " C", uniqueTag(defenderName)
        );
        return new WarFixture(challenger, defender, challengerClan, defenderClan);
    }

    private ClanWarSnapshot acceptedWar(WarFixture fixture) throws SQLException {
        ClanWarSnapshot challenged = wars.challenge(
                UUID.randomUUID(),
                fixture.challenger().playerId(),
                fixture.challengerClan().clanId(),
                fixture.defenderClan().clanId()
        );
        return wars.accept(UUID.randomUUID(), challenged.warId(), fixture.defender().playerId());
    }

    private void setAndLockRosters(WarFixture fixture, UUID warId) throws SQLException {
        wars.setRoster(
                UUID.randomUUID(), warId, fixture.challenger().playerId(), fixture.challengerClan().clanId(),
                List.of(fixture.challenger().playerId())
        );
        wars.setRoster(
                UUID.randomUUID(), warId, fixture.defender().playerId(), fixture.defenderClan().clanId(),
                List.of(fixture.defender().playerId())
        );
        wars.lockRoster(UUID.randomUUID(), warId);
    }

    private ClanWarLoadoutRepository permissiveLoadouts() {
        return new ClanWarLoadoutRepository(
                dataSource,
                catalog,
                (playerId, itemId, currentPayload, nextPayload) -> { }
        );
    }

    private PlayerContext playerWithSession(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease opened = sessions.openSession(playerId, "paper-a", null, LEASE);
        long stateVersion = states.commit(
                opened.sessionId(), "paper-a", opened.stateVersion(), "city", "spawn", payload
        );
        SessionLease refreshed = sessions.heartbeat(opened.sessionId(), "paper-a", LEASE);
        assertEquals(stateVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void assertPendingReturn(UUID itemId, UUID playerId, long expectedItemVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT d.delivery_id, i.location_kind, i.location_id, i.state_version
                     FROM pending_unique_deliveries d
                     JOIN item_instances i ON i.item_instance_id = d.item_instance_id
                     WHERE d.item_instance_id = ?
                       AND d.recipient_player_id = ?
                       AND d.status = 'PENDING'
                     """)) {
            statement.setObject(1, itemId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                UUID deliveryId = row.getObject("delivery_id", UUID.class);
                assertEquals("PENDING_DELIVERY", row.getString("location_kind"));
                assertEquals(deliveryId, row.getObject("location_id", UUID.class));
                assertEquals(expectedItemVersion, row.getLong("state_version"));
            }
        }
    }

    private void assertItemCustody(UUID itemId, String kind, UUID locationId, long version) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version
                     FROM item_instances WHERE item_instance_id = ?
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

    private long warResultCount(UUID warId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM clan_war_results WHERE war_id = ?")) {
            statement.setObject(1, warId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long activeWarItemCount(UUID warId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM clan_war_items WHERE war_id = ? AND released_at IS NULL
                     """)) {
            statement.setObject(1, warId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long liveRosterCount(UUID warId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM clan_war_rosters WHERE war_id = ? AND released_at IS NULL
                     """)) {
            statement.setObject(1, warId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private void mutateWarResult(UUID warId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_war_results
                     SET challenger_rating_after = challenger_rating_after + 1
                     WHERE war_id = ?
                     """)) {
            statement.setObject(1, warId);
            statement.executeUpdate();
        }
    }

    private void rawLockRoster(UUID warId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_wars
                     SET status = 'ROSTER_LOCKED', state_version = state_version + 1
                     WHERE war_id = ? AND status = 'ACCEPTED'
                     """)) {
            statement.setObject(1, warId);
            statement.executeUpdate();
        }
    }

    private void rawCompleteWithoutResult(UUID warId, UUID winningClanId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE clan_wars
                    SET status = 'COMPLETED',
                        winning_clan_id = ?,
                        settlement_operation_id = ?,
                        resolution_operation_id = settlement_operation_id,
                        finished_at = NOW(),
                        state_version = state_version + 1
                    WHERE war_id = ? AND status = 'ACTIVE'
                    """)) {
                UUID operationId = UUID.randomUUID();
                statement.setObject(1, winningClanId);
                statement.setObject(2, operationId);
                statement.setObject(3, warId);
                assertEquals(1, statement.executeUpdate());
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static String uniqueTag(String seed) {
        String compact = seed.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return (compact + UUID.randomUUID().toString().substring(0, 4).toUpperCase()).substring(0, 6);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerContext(UUID playerId, SessionLease session) { }

    private record WarFixture(
            PlayerContext challenger,
            PlayerContext defender,
            ClanSnapshot challengerClan,
            ClanSnapshot defenderClan
    ) { }
}
