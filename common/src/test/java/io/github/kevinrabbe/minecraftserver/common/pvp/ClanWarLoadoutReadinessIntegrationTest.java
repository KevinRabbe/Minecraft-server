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
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanWarLoadoutReadinessIntegrationTest {
    private static final String BACKEND = "paper-war-readiness";
    private static final String ITEM = "war.readiness_sword";
    private static final Duration SESSION_LEASE = Duration.ofMinutes(5);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ClanMembershipRepository memberships;
    private ClanWarLifecycleRepository wars;
    private ClanWarLoadoutReadinessRepository readiness;
    private ClanWarLoadoutRepository loadouts;
    private UniqueItemAuthorityRepository items;
    private CompetitiveDispatchRepository dispatch;

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
        memberships = new ClanMembershipRepository(dataSource);
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        readiness = new ClanWarLoadoutReadinessRepository(dataSource);

        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                ITEM,
                "IRON_SWORD",
                "Readiness Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        loadouts = new ClanWarLoadoutRepository(
                dataSource,
                catalog,
                (playerId, itemInstanceId, currentPayload, nextPayload) -> { }
        );

        CompetitiveExecutionRepository executions = new CompetitiveExecutionRepository(
                dataSource,
                Duration.ofMinutes(1),
                Duration.ofMinutes(5)
        );
        dispatch = new CompetitiveDispatchRepository(
                dataSource,
                executions,
                Duration.ofMinutes(1),
                Duration.ofSeconds(60)
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        clan_war_loadout_confirmations,
                        competitive_player_execution_reservations,
                        competitive_execution_participants,
                        competitive_execution_specs,
                        competitive_result_reports,
                        competitive_executions,
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
    void rosterLockAloneCannotDispatchAndEveryLiveRosterPlayerMustFinalize() throws Exception {
        WarFixture fixture = lockedWar("ReadyWarA", "ReadyWarB");

        assertTrue(findWarCandidate(fixture.war().warId()).isEmpty());

        readiness.confirm(UUID.randomUUID(), fixture.war().warId(), fixture.challenger().playerId());
        assertTrue(findWarCandidate(fixture.war().warId()).isEmpty());

        readiness.confirm(UUID.randomUUID(), fixture.war().warId(), fixture.defender().playerId());
        CompetitiveDispatchCandidate candidate = findWarCandidate(fixture.war().warId()).orElseThrow();
        assertEquals(CompetitiveActivityKind.CLAN_WAR, candidate.activityKind());
        assertEquals(fixture.war().warId(), candidate.activityId());
    }

    @Test
    void custodyMutationInvalidatesConfirmationAndOldOperationReplayCannotRefinalizeIt() throws Exception {
        WarFixture fixture = lockedWar("ReadyMutA", "ReadyMutB");
        UUID challengerConfirmationOperation = UUID.randomUUID();
        ClanWarLoadoutConfirmation original = readiness.confirm(
                challengerConfirmationOperation,
                fixture.war().warId(),
                fixture.challenger().playerId()
        );
        readiness.confirm(UUID.randomUUID(), fixture.war().warId(), fixture.defender().playerId());
        assertTrue(findWarCandidate(fixture.war().warId()).isPresent());

        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(),
                ITEM,
                fixture.challenger().playerId(),
                "test.war_readiness_item",
                fixture.challenger().playerId()
        );
        loadouts.depositPlayerItem(
                UUID.randomUUID(),
                fixture.war().warId(),
                fixture.challenger().session().sessionId(),
                BACKEND,
                fixture.challenger().session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "spawn",
                new byte[]{0},
                "test.war_readiness_deposit"
        );

        assertTrue(readiness.load(fixture.war().warId(), fixture.challenger().playerId()).isEmpty());
        assertTrue(findWarCandidate(fixture.war().warId()).isEmpty());

        ClanWarLoadoutConfirmation replay = readiness.confirm(
                challengerConfirmationOperation,
                fixture.war().warId(),
                fixture.challenger().playerId()
        );
        assertEquals(original, replay);
        assertTrue(
                readiness.load(fixture.war().warId(), fixture.challenger().playerId()).isEmpty(),
                "replaying a stale confirmation operation must not re-finalize a later custody selection"
        );
        assertTrue(findWarCandidate(fixture.war().warId()).isEmpty());

        readiness.confirm(UUID.randomUUID(), fixture.war().warId(), fixture.challenger().playerId());
        assertTrue(findWarCandidate(fixture.war().warId()).isPresent());
    }

    @Test
    void onlyLiveRosterPlayersCanFinalizeAndConfirmationOperationIsRequestBound() throws Exception {
        WarFixture fixture = lockedWar("ReadyAuthA", "ReadyAuthB");
        Player outsider = player("ReadyOutsider");

        assertThrows(
                ClanWarException.class,
                () -> readiness.confirm(UUID.randomUUID(), fixture.war().warId(), outsider.playerId())
        );

        UUID operationId = UUID.randomUUID();
        ClanWarLoadoutConfirmation first = readiness.confirm(
                operationId,
                fixture.war().warId(),
                fixture.challenger().playerId()
        );
        assertEquals(
                first,
                readiness.confirm(operationId, fixture.war().warId(), fixture.challenger().playerId())
        );
        assertThrows(
                ClanWarException.class,
                () -> readiness.confirm(operationId, fixture.war().warId(), fixture.defender().playerId())
        );
    }

    private WarFixture lockedWar(String challengerName, String defenderName) throws SQLException {
        Player challenger = player(challengerName);
        Player defender = player(defenderName);
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), challengerName + " Clan", randomTag()
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), defenderName + " Clan", randomTag()
        );
        ClanWarSnapshot war = wars.challenge(
                UUID.randomUUID(), challenger.playerId(), challengerClan.clanId(), defenderClan.clanId()
        );
        wars.accept(UUID.randomUUID(), war.warId(), defender.playerId());
        wars.setRoster(
                UUID.randomUUID(), war.warId(), challenger.playerId(), challengerClan.clanId(),
                List.of(challenger.playerId())
        );
        wars.setRoster(
                UUID.randomUUID(), war.warId(), defender.playerId(), defenderClan.clanId(),
                List.of(defender.playerId())
        );
        ClanWarSnapshot locked = wars.lockRoster(UUID.randomUUID(), war.warId());
        return new WarFixture(locked, challenger, defender);
    }

    private Player player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, name);
        SessionLease session = sessions.openSession(playerId, BACKEND, null, SESSION_LEASE);
        return new Player(playerId, minecraftUuid, session);
    }

    private java.util.Optional<CompetitiveDispatchCandidate> findWarCandidate(UUID warId) throws SQLException {
        return dispatch.listReadyActivities(100).stream()
                .filter(candidate -> candidate.activityKind() == CompetitiveActivityKind.CLAN_WAR)
                .filter(candidate -> candidate.activityId().equals(warId))
                .findFirst();
    }

    private static String randomTag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record Player(UUID playerId, UUID minecraftUuid, SessionLease session) { }

    private record WarFixture(ClanWarSnapshot war, Player challenger, Player defender) { }
}
