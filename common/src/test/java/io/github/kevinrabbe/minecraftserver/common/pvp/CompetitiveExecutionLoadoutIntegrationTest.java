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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveExecutionLoadoutIntegrationTest {
    private static final String PAPER_BACKEND = "paper-execution-loadout";
    private static final String LEGACY_BACKEND = "legacy-execution-loadout";
    private static final String SWORD = "war.snapshot_sword";
    private static final String BOW = "war.snapshot_bow";
    private static final Duration SESSION_LEASE = Duration.ofMinutes(5);
    private static final Duration EXECUTION_LEASE = Duration.ofSeconds(60);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ClanMembershipRepository memberships;
    private ClanWarLifecycleRepository wars;
    private ClanWarLoadoutRepository loadouts;
    private ClanWarLoadoutReadinessRepository readiness;
    private UniqueItemAuthorityRepository items;
    private CompetitiveExecutionRepository executions;
    private CompetitiveDispatchRepository dispatch;
    private CompetitiveExecutionLoadoutRepository executionLoadouts;

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

        ItemCatalog catalog = new ItemCatalog(List.of(
                new ItemDefinition(SWORD, "IRON_SWORD", "Snapshot Sword", 1, ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL),
                new ItemDefinition(BOW, "BOW", "Snapshot Bow", 1, ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL)
        ));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        loadouts = new ClanWarLoadoutRepository(
                dataSource,
                catalog,
                (playerId, itemInstanceId, currentPayload, nextPayload) -> { }
        );
        readiness = new ClanWarLoadoutReadinessRepository(dataSource);
        executions = new CompetitiveExecutionRepository(dataSource, Duration.ofMinutes(1), Duration.ofMinutes(5));
        dispatch = new CompetitiveDispatchRepository(
                dataSource,
                executions,
                Duration.ofMinutes(1),
                EXECUTION_LEASE
        );
        executionLoadouts = new CompetitiveExecutionLoadoutRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        competitive_execution_loadout_items,
                        clan_war_loadout_confirmations,
                        competitive_player_execution_reservations,
                        competitive_runtime_principals,
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
                        players,
                        backends
                    RESTART IDENTITY CASCADE
                    """);
            statement.execute("""
                    INSERT INTO backends(backend_id, status, player_count)
                    VALUES ('legacy-execution-loadout', 'ONLINE', 0)
                    """);
            statement.execute("""
                    INSERT INTO competitive_runtime_principals(
                        database_role,
                        backend_id,
                        max_execution_lease_seconds,
                        dispatch_enabled,
                        max_active_executions
                    ) VALUES ('execution-loadout-runtime', 'legacy-execution-loadout', 120, TRUE, 4)
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void assignmentFreezesDeterministicIdentityFreeCustodySnapshot() throws Exception {
        WarFixture fixture = lockedWar();
        UniqueItemAuthorityResult challengerSword = createAndDeposit(
                fixture.war().warId(), fixture.challenger(), SWORD, "snapshot.challenger_sword"
        );
        UniqueItemAuthorityResult challengerBow = createAndDeposit(
                fixture.war().warId(), fixture.challenger(), BOW, "snapshot.challenger_bow"
        );
        UniqueItemAuthorityResult defenderSword = createAndDeposit(
                fixture.war().warId(), fixture.defender(), SWORD, "snapshot.defender_sword"
        );

        assertThrows(
                SQLException.class,
                () -> executions.assign(
                        UUID.randomUUID(),
                        CompetitiveActivityKind.CLAN_WAR,
                        fixture.war().warId(),
                        LEGACY_BACKEND,
                        EXECUTION_LEASE
                ),
                "runtime-manifest trigger must reject direct assignment before finalized loadouts"
        );

        readiness.confirm(UUID.randomUUID(), fixture.war().warId(), fixture.challenger().playerId());
        readiness.confirm(UUID.randomUUID(), fixture.war().warId(), fixture.defender().playerId());

        CompetitiveDispatchCandidate candidate = dispatch.listReadyActivities(10).stream()
                .filter(value -> value.activityKind() == CompetitiveActivityKind.CLAN_WAR)
                .filter(value -> value.activityId().equals(fixture.war().warId()))
                .findFirst()
                .orElseThrow();
        CompetitiveExecutionSnapshot assigned = dispatch.dispatch(UUID.randomUUID(), candidate).orElseThrow();
        assertEquals(CompetitiveExecutionStatus.ASSIGNED, assigned.status());

        List<CompetitiveExecutionLoadoutItem> snapshot = executionLoadouts.list(assigned.executionId(), 20);
        assertEquals(3, snapshot.size());
        assertEquals(List.of(0, 0, 1), snapshot.stream().map(CompetitiveExecutionLoadoutItem::participantIndex).toList());
        assertEquals(List.of(0, 1, 0), snapshot.stream().map(CompetitiveExecutionLoadoutItem::loadoutItemIndex).toList());
        assertEquals(List.of(BOW, SWORD, SWORD), snapshot.stream().map(CompetitiveExecutionLoadoutItem::definitionId).toList());
        assertTrue(snapshot.stream().allMatch(item -> item.upgradeLevel() == 0));
        assertTrue(snapshot.stream().allMatch(item -> item.rollStateJson() != null && !item.rollStateJson().isBlank()));

        List<String> columns = loadoutColumns();
        assertEquals(List.of(
                "execution_id",
                "participant_index",
                "loadout_item_index",
                "definition_id",
                "roll_state",
                "upgrade_level"
        ), columns);
        assertTrue(columns.stream().noneMatch(column -> column.contains("item_instance")));

        assertThrows(SQLException.class, () -> updateSnapshotDefinition(assigned.executionId()));
        assertThrows(SQLException.class, () -> deleteSnapshot(assigned.executionId()));

        // Persistent custody still owns these UUIDs; none of them are present in the execution snapshot schema/data.
        assertTrue(itemStillInWarCustody(challengerSword.itemInstanceId(), fixture.war().warId()));
        assertTrue(itemStillInWarCustody(challengerBow.itemInstanceId(), fixture.war().warId()));
        assertTrue(itemStillInWarCustody(defenderSword.itemInstanceId(), fixture.war().warId()));
    }

    @Test
    void rankedExecutionHasNoClanWarLoadoutRows() throws Exception {
        Player playerA = player("SnapRankA");
        Player playerB = player("SnapRankB");
        RankedArenaRepository ranked = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());

        CompetitiveExecutionSnapshot assigned = dispatch.dispatch(
                UUID.randomUUID(),
                dispatch.listReadyActivities(10).stream()
                        .filter(candidate -> candidate.activityKind() == CompetitiveActivityKind.RANKED_ARENA)
                        .filter(candidate -> candidate.activityId().equals(match.matchId()))
                        .findFirst()
                        .orElseThrow()
        ).orElseThrow();

        assertTrue(executionLoadouts.list(assigned.executionId(), 10).isEmpty());
    }

    @Test
    void loadoutReadLimitIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> executionLoadouts.list(UUID.randomUUID(), 0));
        assertThrows(IllegalArgumentException.class, () -> executionLoadouts.list(UUID.randomUUID(), 501));
    }

    private WarFixture lockedWar() throws SQLException {
        Player challenger = player("SnapWarA");
        Player defender = player("SnapWarB");
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), "Snapshot Alpha", randomTag()
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), "Snapshot Beta", randomTag()
        );
        ClanWarSnapshot challenged = wars.challenge(
                UUID.randomUUID(), challenger.playerId(), challengerClan.clanId(), defenderClan.clanId()
        );
        wars.accept(UUID.randomUUID(), challenged.warId(), defender.playerId());
        wars.setRoster(
                UUID.randomUUID(), challenged.warId(), challenger.playerId(), challengerClan.clanId(),
                List.of(challenger.playerId())
        );
        wars.setRoster(
                UUID.randomUUID(), challenged.warId(), defender.playerId(), defenderClan.clanId(),
                List.of(defender.playerId())
        );
        ClanWarSnapshot locked = wars.lockRoster(UUID.randomUUID(), challenged.warId());
        return new WarFixture(locked, challenger, defender);
    }

    private UniqueItemAuthorityResult createAndDeposit(
            UUID warId,
            Player player,
            String definitionId,
            String reason
    ) throws SQLException {
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), definitionId, player.playerId(), reason + ".create", player.playerId()
        );
        ClanWarCustodyDepositResult deposit = loadouts.depositPlayerItem(
                UUID.randomUUID(),
                warId,
                player.session().sessionId(),
                PAPER_BACKEND,
                player.session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "spawn",
                new byte[]{1},
                reason + ".deposit"
        );
        player.session = new SessionLease(
                player.session().sessionId(),
                player.playerId(),
                PAPER_BACKEND,
                deposit.playerStateVersion(),
                player.session().status(),
                player.session().leaseExpiresAt()
        );
        return item;
    }

    private Player player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, name);
        return new Player(playerId, minecraftUuid, sessions.openSession(playerId, PAPER_BACKEND, null, SESSION_LEASE));
    }

    private List<String> loadoutColumns() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_execution_loadout_items LIMIT 0"
             );
             ResultSet rows = statement.executeQuery()) {
            ResultSetMetaData metadata = rows.getMetaData();
            ArrayList<String> result = new ArrayList<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                result.add(metadata.getColumnLabel(index));
            }
            return List.copyOf(result);
        }
    }

    private void updateSnapshotDefinition(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE competitive_execution_loadout_items
                     SET definition_id = 'forged.definition'
                     WHERE execution_id = ?
                     """)) {
            statement.setObject(1, executionId);
            statement.executeUpdate();
        }
    }

    private void deleteSnapshot(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM competitive_execution_loadout_items
                     WHERE execution_id = ?
                     """)) {
            statement.setObject(1, executionId);
            statement.executeUpdate();
        }
    }

    private boolean itemStillInWarCustody(UUID itemInstanceId, UUID warId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1
                     FROM item_instances
                     WHERE item_instance_id = ?
                       AND location_kind = 'WAR_CUSTODY'
                       AND location_id = ?
                     """)) {
            statement.setObject(1, itemInstanceId);
            statement.setObject(2, warId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
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

    private static final class Player {
        private final UUID playerId;
        private final UUID minecraftUuid;
        private SessionLease session;

        private Player(UUID playerId, UUID minecraftUuid, SessionLease session) {
            this.playerId = playerId;
            this.minecraftUuid = minecraftUuid;
            this.session = session;
        }

        private UUID playerId() {
            return playerId;
        }

        private UUID minecraftUuid() {
            return minecraftUuid;
        }

        private SessionLease session() {
            return session;
        }
    }

    private record WarFixture(ClanWarSnapshot war, Player challenger, Player defender) { }
}
