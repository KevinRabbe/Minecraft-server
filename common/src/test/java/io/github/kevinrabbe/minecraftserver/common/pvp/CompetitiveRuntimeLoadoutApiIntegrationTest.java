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
class CompetitiveRuntimeLoadoutApiIntegrationTest {
    private static final String PAPER_BACKEND = "paper-runtime-loadout-api";
    private static final String LEGACY_BACKEND = "legacy-runtime-loadout-api";
    private static final String OTHER_BACKEND = "legacy-runtime-loadout-other";
    private static final String SWORD = "war.runtime_sword";
    private static final String BOW = "war.runtime_bow";
    private static final Duration SESSION_LEASE = Duration.ofMinutes(5);
    private static final Duration EXECUTION_LEASE = Duration.ofMinutes(2);

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
    private CompetitiveExecutionService service;
    private UUID runtimeIncarnation;

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
                new ItemDefinition(SWORD, "IRON_SWORD", "Runtime Sword", 1, ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL),
                new ItemDefinition(BOW, "BOW", "Runtime Bow", 1, ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL)
        ));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        loadouts = new ClanWarLoadoutRepository(
                dataSource,
                catalog,
                (playerId, itemInstanceId, currentPayload, nextPayload) -> { }
        );
        readiness = new ClanWarLoadoutReadinessRepository(dataSource);
        executions = new CompetitiveExecutionRepository(dataSource, Duration.ofMinutes(1), Duration.ofMinutes(5));
        service = new CompetitiveExecutionService(
                executions,
                new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1()),
                wars,
                new ClanWarResolutionRepository(dataSource)
        );
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        runtimeIncarnation = UUID.randomUUID();
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
                    VALUES
                        ('paper-runtime-loadout-api', 'ONLINE', 0),
                        ('legacy-runtime-loadout-api', 'ONLINE', 0),
                        ('legacy-runtime-loadout-other', 'ONLINE', 0)
                    """);
            statement.execute("""
                    INSERT INTO competitive_runtime_principals(
                        database_role,
                        backend_id,
                        max_execution_lease_seconds,
                        dispatch_enabled,
                        max_active_executions
                    ) VALUES (SESSION_USER::TEXT, 'legacy-runtime-loadout-api', 120, TRUE, 4)
                    """);
        }
        registerCurrentRuntime();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void runtimePagesExactIdentityFreeFrozenLoadoutAndIsBackendBound() throws Exception {
        WarFixture fixture = lockedWar();
        createAndDeposit(fixture.war().warId(), fixture.challenger(), SWORD, "runtime.challenger_sword");
        createAndDeposit(fixture.war().warId(), fixture.challenger(), BOW, "runtime.challenger_bow");
        createAndDeposit(fixture.war().warId(), fixture.defender(), SWORD, "runtime.defender_sword");
        readiness.confirm(UUID.randomUUID(), fixture.war().warId(), fixture.challenger().playerId());
        readiness.confirm(UUID.randomUUID(), fixture.war().warId(), fixture.defender().playerId());

        CompetitiveExecutionSnapshot assigned = executions.assign(
                UUID.randomUUID(),
                CompetitiveActivityKind.CLAN_WAR,
                fixture.war().warId(),
                LEGACY_BACKEND,
                EXECUTION_LEASE
        );
        CompetitiveExecutionSnapshot active = service.activate(
                assigned.executionId(), LEGACY_BACKEND, EXECUTION_LEASE
        );

        List<RuntimeLoadoutRow> firstPage = runtimeLoadoutPage(active.executionId(), null, null, 2);
        assertEquals(2, firstPage.size());
        assertEquals(List.of(0, 0), firstPage.stream().map(RuntimeLoadoutRow::participantIndex).toList());
        assertEquals(List.of(0, 1), firstPage.stream().map(RuntimeLoadoutRow::loadoutItemIndex).toList());
        assertEquals(List.of(BOW, SWORD), firstPage.stream().map(RuntimeLoadoutRow::definitionId).toList());

        RuntimeLoadoutRow cursor = firstPage.getLast();
        List<RuntimeLoadoutRow> secondPage = runtimeLoadoutPage(
                active.executionId(), cursor.participantIndex(), cursor.loadoutItemIndex(), 2
        );
        assertEquals(1, secondPage.size());
        assertEquals(1, secondPage.getFirst().participantIndex());
        assertEquals(0, secondPage.getFirst().loadoutItemIndex());
        assertEquals(SWORD, secondPage.getFirst().definitionId());
        assertTrue(secondPage.getFirst().rollStateJson() != null && !secondPage.getFirst().rollStateJson().isBlank());
        assertEquals(0, secondPage.getFirst().upgradeLevel());

        assertEquals(List.of(
                "participant_index",
                "loadout_item_index",
                "definition_id",
                "roll_state_json",
                "upgrade_level"
        ), runtimeLoadoutColumns(active.executionId()));
        assertTrue(runtimeLoadoutColumns(active.executionId()).stream().noneMatch(column -> column.contains("item_instance")));

        remapCurrentLogin(OTHER_BACKEND);
        assertTrue(
                runtimeLoadoutPage(active.executionId(), null, null, 10).isEmpty(),
                "a runtime principal must not see another backend's frozen loadout"
        );
    }

    @Test
    void runtimeLoadoutPageInputsAreBoundedWithoutInventingKitLimits() {
        UUID executionId = UUID.randomUUID();
        assertThrows(SQLException.class, () -> runtimeLoadoutPage(executionId, null, null, 0));
        assertThrows(SQLException.class, () -> runtimeLoadoutPage(executionId, null, null, 501));
        assertThrows(SQLException.class, () -> runtimeLoadoutPage(executionId, 0, null, 10));
        assertThrows(SQLException.class, () -> runtimeLoadoutPage(executionId, null, 0, 10));
        assertThrows(SQLException.class, () -> runtimeLoadoutPage(executionId, -1, 0, 10));
        assertThrows(SQLException.class, () -> runtimeLoadoutPage(executionId, 0, -1, 10));
    }

    private WarFixture lockedWar() throws SQLException {
        Player challenger = player("RuntimeWarA");
        Player defender = player("RuntimeWarB");
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), "Runtime Alpha", randomTag()
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), "Runtime Beta", randomTag()
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
        return new WarFixture(wars.lockRoster(UUID.randomUUID(), challenged.warId()), challenger, defender);
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
                player.session().ownerInstanceId(),
                deposit.playerStateVersion(),
                player.session().status(),
                player.session().leaseExpiresAt()
        );
        return item;
    }

    private Player player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, name);
        SessionLease session = sessions.openSession(playerId, PAPER_BACKEND, null, SESSION_LEASE);
        return new Player(playerId, session);
    }

    private List<RuntimeLoadoutRow> runtimeLoadoutPage(
            UUID executionId,
            Integer afterParticipantIndex,
            Integer afterLoadoutItemIndex,
            int limit
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_page_loadout(?, ?, ?, ?, ?)"
             )) {
            statement.setObject(1, runtimeIncarnation);
            statement.setObject(2, executionId);
            if (afterParticipantIndex == null) statement.setNull(3, java.sql.Types.INTEGER);
            else statement.setInt(3, afterParticipantIndex);
            if (afterLoadoutItemIndex == null) statement.setNull(4, java.sql.Types.INTEGER);
            else statement.setInt(4, afterLoadoutItemIndex);
            statement.setInt(5, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<RuntimeLoadoutRow> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new RuntimeLoadoutRow(
                            rows.getInt("participant_index"),
                            rows.getInt("loadout_item_index"),
                            rows.getString("definition_id"),
                            rows.getString("roll_state_json"),
                            rows.getInt("upgrade_level")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private List<String> runtimeLoadoutColumns(UUID executionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM competitive_runtime_page_loadout(?, ?, NULL, NULL, 1)"
             )) {
            statement.setObject(1, runtimeIncarnation);
            statement.setObject(2, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                ResultSetMetaData metadata = rows.getMetaData();
                ArrayList<String> result = new ArrayList<>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    result.add(metadata.getColumnLabel(index));
                }
                return List.copyOf(result);
            }
        }
    }

    private void remapCurrentLogin(String backendId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE competitive_runtime_principals
                     SET backend_id = ?
                     WHERE database_role = SESSION_USER::TEXT
                     """)) {
            statement.setString(1, backendId);
            assertEquals(1, statement.executeUpdate());
        }
        registerCurrentRuntime();
    }

    private void registerCurrentRuntime() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT competitive_runtime_register(?, 0)"
             )) {
            statement.setObject(1, runtimeIncarnation);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
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
        private SessionLease session;

        private Player(UUID playerId, SessionLease session) {
            this.playerId = playerId;
            this.session = session;
        }

        private UUID playerId() {
            return playerId;
        }

        private SessionLease session() {
            return session;
        }
    }

    private record WarFixture(ClanWarSnapshot war, Player challenger, Player defender) { }

    private record RuntimeLoadoutRow(
            int participantIndex,
            int loadoutItemIndex,
            String definitionId,
            String rollStateJson,
            int upgradeLevel
    ) { }
}
