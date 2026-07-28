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
    void lifecycleIsRoleBoundIdempotentAndRequiresExactBothSideRoster() throws Exception {
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
        assertThrows(
                ClanWarException.class,
                () -> wars.challenge(
                        challengeOperation,
                        fixture.challenger().playerId(),
                        fixture.defenderClan().clanId(),
                        fixture.challengerClan().clanId()
                )
        );
        assertThrows(ClanWarException.class, () -> wars.accept(UUID.randomUUID(), challenged.warId(), outsider));

        ClanWarSnapshot accepted = wars.accept(
                UUID.randomUUID(), challenged.warId(), fixture.defender().playerId()
        );
        wars.setRoster(
                UUID.randomUUID(), accepted.warId(), fixture.challenger().playerId(),
                fixture.challengerClan().clanId(), List.of(fixture.challenger().playerId())
        );
        assertThrows(SQLException.class, () -> wars.lockRoster(UUID.randomUUID(), accepted.warId()));
        wars.setRoster(
                UUID.randomUUID(), accepted.warId(), fixture.defender().playerId(),
                fixture.defenderClan().clanId(), List.of(fixture.defender().playerId())
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
    void onePlayerCannotOccupyTwoLiveWarRosters() throws Exception {
        WarFixture first = fixture("WarOneA", "WarOneB");
        ClanWarSnapshot warA = acceptedWar(first);
        wars.setRoster(
                UUID.randomUUID(), warA.warId(), first.challenger().playerId(),
                first.challengerClan().clanId(), List.of(first.challenger().playerId())
        );

        PlayerContext third = playerWithSession("WarOneC", new byte[]{4});
        ClanSnapshot thirdClan = memberships.createClan(UUID.randomUUID(), third.playerId(), "War One C", randomTag());
        ClanWarSnapshot warB = wars.challenge(
                UUID.randomUUID(), third.playerId(), thirdClan.clanId(), first.challengerClan().clanId()
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
    void realGearCrossesIntoWarCustodyOnlyAfterRosterLockAndYieldsReadOnlyCombatSnapshot() throws Exception {
        WarFixture fixture = fixture("WarGearA", "WarGearB");
        ClanWarSnapshot accepted = acceptedWar(fixture);
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), ITEM, fixture.challenger().playerId(), "test.item", fixture.challenger().playerId()
        );
        ClanWarLoadoutRepository permissive = permissiveLoadouts();
        assertThrows(
                ClanWarException.class,
                () -> permissive.depositPlayerItem(
                        UUID.randomUUID(), accepted.warId(), fixture.challenger().session().sessionId(), "paper-a",
                        fixture.challenger().session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                        "city", "spawn", new byte[]{0}, "clan.war_item_entry"
                )
        );

        setAndLockRosters(fixture, accepted.warId());
        AtomicInteger validations = new AtomicInteger();
        ClanWarLoadoutRepository proving = new ClanWarLoadoutRepository(
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
        ClanWarCustodyDepositResult first = proving.depositPlayerItem(
                operationId, accepted.warId(), fixture.challenger().session().sessionId(), "paper-a",
                fixture.challenger().session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[]{0}, "clan.war_item_entry"
        );
        ClanWarCustodyDepositResult retry = proving.depositPlayerItem(
                operationId, accepted.warId(), fixture.challenger().session().sessionId(), "paper-a",
                fixture.challenger().session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[]{0}, "clan.war_item_entry"
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertItemCustody(item.itemInstanceId(), "WAR_CUSTODY", accepted.warId(), item.stateVersion() + 1);
        List<ClanWarCustodiedItemSnapshot> combatSnapshot = proving.loadActiveCombatSnapshot(accepted.warId());
        assertEquals(1, combatSnapshot.size());
        assertEquals(item.itemInstanceId(), combatSnapshot.getFirst().itemInstanceId());
        assertEquals(ITEM, combatSnapshot.getFirst().definitionId());
        assertArrayEquals(new byte[]{0}, states.load(fixture.challenger().playerId()).statePayload());
    }

    @Test
    void completionSettlesClanRatingOnceAndReturnsEveryPersistentItemThroughDelivery() throws Exception {
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
        assertThrows(
                ClanWarException.class,
                () -> resolutions.complete(UUID.randomUUID(), accepted.warId(), fixture.defenderClan().clanId())
        );
    }

    @Test
    void cancelAndFailureRecoverCustodyWithoutChangingClanRating() throws Exception {
        WarFixture cancelFixture = fixture("WarCanA", "WarCanB");
        ClanWarSnapshot cancelWar = acceptedWar(cancelFixture);
        setAndLockRosters(cancelFixture, cancelWar.warId());
        UniqueItemAuthorityResult cancelItem = items.createForPlayer(
                UUID.randomUUID(), ITEM, cancelFixture.challenger().playerId(), "test.item", cancelFixture.challenger().playerId()
        );
        ClanWarCustodyDepositResult cancelCustody = permissiveLoadouts().depositPlayerItem(
                UUID.randomUUID(), cancelWar.warId(), cancelFixture.challenger().session().sessionId(), "paper-a",
                cancelFixture.challenger().session().stateVersion(), cancelItem.itemInstanceId(), cancelItem.stateVersion(),
                "city", "spawn", new byte[]{0}, "clan.war_item_entry"
        );
        UUID cancelOperation = UUID.randomUUID();
        ClanWarTerminalResult cancelled = resolutions.cancel(
                cancelOperation, cancelWar.warId(), cancelFixture.challenger().playerId()
        );
        assertEquals(cancelled, resolutions.cancel(
                cancelOperation, cancelWar.warId(), cancelFixture.challenger().playerId()
        ));
        assertEquals(ClanWarStatus.CANCELLED, cancelled.war().status());
        assertPendingReturn(cancelItem.itemInstanceId(), cancelFixture.challenger().playerId(), cancelCustody.item().itemStateVersion() + 1);
        assertEquals(1000, resolutions.loadRating(cancelFixture.challengerClan().clanId()).orElseThrow().rating());
        assertEquals(1000, resolutions.loadRating(cancelFixture.defenderClan().clanId()).orElseThrow().rating());

        resetDatabase();
        WarFixture failFixture = fixture("WarFailA", "WarFailB");
        ClanWarSnapshot failWar = acceptedWar(failFixture);
        setAndLockRosters(failFixture, failWar.warId());
        UniqueItemAuthorityResult failItem = items.createForPlayer(
                UUID.randomUUID(), ITEM, failFixture.defender().playerId(), "test.item", failFixture.defender().playerId()
        );
        ClanWarCustodyDepositResult failCustody = permissiveLoadouts().depositPlayerItem(
                UUID.randomUUID(), failWar.warId(), failFixture.defender().session().sessionId(), "paper-a",
                failFixture.defender().session().stateVersion(), failItem.itemInstanceId(), failItem.stateVersion(),
                "city", "spawn", new byte[]{0}, "clan.war_item_entry"
        );
        wars.start(UUID.randomUUID(), failWar.warId());
        ClanWarTerminalResult failed = resolutions.fail(UUID.randomUUID(), failWar.warId());
        assertEquals(ClanWarStatus.FAILED, failed.war().status());
        assertEquals(0L, warResultCount(failWar.warId()));
        assertPendingReturn(failItem.itemInstanceId(), failFixture.defender().playerId(), failCustody.item().itemStateVersion() + 1);
        assertEquals(1000, resolutions.loadRating(failFixture.challengerClan().clanId()).orElseThrow().rating());
        assertEquals(1000, resolutions.loadRating(failFixture.defenderClan().clanId()).orElseThrow().rating());
    }

    @Test
    void concurrentCompletionAndDeferredDatabaseChecksPreventDoubleSettlementOrStrandedTerminalCustody() throws Exception {
        WarFixture race = fixture("WarRaceA", "WarRaceB");
        ClanWarSnapshot raceWar = acceptedWar(race);
        setAndLockRosters(race, raceWar.warId());
        wars.start(UUID.randomUUID(), raceWar.warId());

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ClanWarCompletionResult> first = executor.submit(() -> resolutions.complete(
                    UUID.randomUUID(), raceWar.warId(), race.challengerClan().clanId()
            ));
            Future<ClanWarCompletionResult> second = executor.submit(() -> resolutions.complete(
                    UUID.randomUUID(), raceWar.warId(), race.defenderClan().clanId()
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
        assertEquals(1L, warResultCount(raceWar.warId()));
        assertEquals(2000,
                resolutions.loadRating(race.challengerClan().clanId()).orElseThrow().rating()
                        + resolutions.loadRating(race.defenderClan().clanId()).orElseThrow().rating());

        resetDatabase();
        WarFixture noResult = fixture("WarDbA", "WarDbB");
        ClanWarSnapshot resultlessWar = acceptedWar(noResult);
        setAndLockRosters(noResult, resultlessWar.warId());
        wars.start(UUID.randomUUID(), resultlessWar.warId());
        assertThrows(
                SQLException.class,
                () -> rawCompleteWithoutResult(resultlessWar.warId(), noResult.challengerClan().clanId())
        );
        assertEquals(ClanWarStatus.ACTIVE, wars.loadWar(resultlessWar.warId()).orElseThrow().status());

        resetDatabase();
        WarFixture stranded = fixture("WarDbC", "WarDbD");
        ClanWarSnapshot strandedWar = acceptedWar(stranded);
        setAndLockRosters(stranded, strandedWar.warId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), ITEM, stranded.challenger().playerId(), "test.item", stranded.challenger().playerId()
        );
        permissiveLoadouts().depositPlayerItem(
                UUID.randomUUID(), strandedWar.warId(), stranded.challenger().session().sessionId(), "paper-a",
                stranded.challenger().session().stateVersion(), item.itemInstanceId(), item.stateVersion(),
                "city", "spawn", new byte[]{0}, "clan.war_item_entry"
        );
        assertThrows(SQLException.class, () -> rawFailWithoutCustodyRelease(strandedWar.warId()));
        assertEquals(ClanWarStatus.ROSTER_LOCKED, wars.loadWar(strandedWar.warId()).orElseThrow().status());
        assertItemCustody(item.itemInstanceId(), "WAR_CUSTODY", strandedWar.warId(), item.stateVersion() + 1);
    }

    private WarFixture fixture(String challengerName, String defenderName) throws SQLException {
        PlayerContext challenger = playerWithSession(challengerName, new byte[]{1});
        PlayerContext defender = playerWithSession(defenderName, new byte[]{1});
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), challengerName + " C", randomTag()
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), defenderName + " C", randomTag()
        );
        return new WarFixture(challenger, defender, challengerClan, defenderClan);
    }

    private ClanWarSnapshot acceptedWar(WarFixture fixture) throws SQLException {
        ClanWarSnapshot challenged = wars.challenge(
                UUID.randomUUID(), fixture.challenger().playerId(),
                fixture.challengerClan().clanId(), fixture.defenderClan().clanId()
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
        return new ClanWarLoadoutRepository(dataSource, catalog, (playerId, itemId, currentPayload, nextPayload) -> { });
    }

    private PlayerContext playerWithSession(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease opened = sessions.openSession(playerId, "paper-a", null, LEASE);
        long stateVersion = states.commit(opened.sessionId(), "paper-a", opened.stateVersion(), "city", "spawn", payload);
        SessionLease refreshed = sessions.heartbeat(opened.sessionId(), "paper-a", LEASE);
        assertEquals(stateVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void assertPendingReturn(UUID itemId, UUID playerId, long expectedVersion) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT d.delivery_id, i.location_kind, i.location_id, i.state_version
                     FROM pending_unique_deliveries d
                     JOIN item_instances i ON i.item_instance_id = d.item_instance_id
                     WHERE d.item_instance_id = ? AND d.recipient_player_id = ? AND d.status = 'PENDING'
                     """)) {
            statement.setObject(1, itemId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                UUID deliveryId = row.getObject("delivery_id", UUID.class);
                assertEquals("PENDING_DELIVERY", row.getString("location_kind"));
                assertEquals(deliveryId, row.getObject("location_id", UUID.class));
                assertEquals(expectedVersion, row.getLong("state_version"));
            }
        }
    }

    private void assertItemCustody(UUID itemId, String kind, UUID locationId, long version) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT location_kind, location_id, state_version FROM item_instances WHERE item_instance_id = ?
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
        return count("SELECT COUNT(*) FROM clan_war_results WHERE war_id = ?", warId);
    }

    private long activeWarItemCount(UUID warId) throws SQLException {
        return count("SELECT COUNT(*) FROM clan_war_items WHERE war_id = ? AND released_at IS NULL", warId);
    }

    private long liveRosterCount(UUID warId) throws SQLException {
        return count("SELECT COUNT(*) FROM clan_war_rosters WHERE war_id = ? AND released_at IS NULL", warId);
    }

    private long count(String sql, UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
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

    private void rawCompleteWithoutResult(UUID warId, UUID winningClanId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE clan_wars
                    SET status = 'COMPLETED',
                        winning_clan_id = ?,
                        settlement_operation_id = ?,
                        resolution_operation_id = ?,
                        finished_at = NOW(),
                        state_version = state_version + 1
                    WHERE war_id = ? AND status = 'ACTIVE'
                    """)) {
                UUID operationId = UUID.randomUUID();
                statement.setObject(1, winningClanId);
                statement.setObject(2, operationId);
                statement.setObject(3, operationId);
                statement.setObject(4, warId);
                assertEquals(1, statement.executeUpdate());
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void rawFailWithoutCustodyRelease(UUID warId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE clan_wars
                    SET status = 'FAILED',
                        resolution_operation_id = ?,
                        finished_at = NOW(),
                        state_version = state_version + 1
                    WHERE war_id = ? AND status = 'ROSTER_LOCKED'
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, warId);
                assertEquals(1, statement.executeUpdate());
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static String randomTag() {
        return ("W" + UUID.randomUUID().toString().replace("-", "").substring(0, 5)).toUpperCase();
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
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
