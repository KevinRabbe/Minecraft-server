package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveRuntimeManifestRepositoryIntegrationTest {
    private static final String BACKEND = "legacy-manifest";
    private static final Duration LEASE = Duration.ofMinutes(2);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private BackendRegistry backends;
    private RankedArenaRepository ranked;
    private ClanWarLifecycleRepository wars;
    private CompetitiveExecutionRepository executions;
    private CompetitiveRuntimeManifestRepository manifests;

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
        memberships = new ClanMembershipRepository(dataSource);
        backends = new BackendRegistry(dataSource);
        ranked = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        executions = new CompetitiveExecutionRepository(
                dataSource,
                Duration.ofMinutes(1),
                Duration.ofMinutes(5)
        );
        manifests = new CompetitiveRuntimeManifestRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        competitive_execution_participants,
                        competitive_execution_specs,
                        competitive_result_reports,
                        competitive_executions,
                        clan_war_results,
                        clan_war_items,
                        clan_war_rosters,
                        clan_wars,
                        clan_war_ratings,
                        ranked_match_results,
                        ranked_match_participants,
                        ranked_matches,
                        ranked_ratings,
                        clan_invitations,
                        clan_commodity_balances,
                        clan_treasuries,
                        clan_members,
                        clans,
                        processed_operations,
                        player_sessions,
                        player_state,
                        player_names,
                        wallets,
                        players,
                        backends
                    RESTART IDENTITY CASCADE
                    """);
        }
        backends.registerOnline(BACKEND, 0);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void rankedAssignmentAtomicallyMaterializesOnlyRuntimeIdentity() throws Exception {
        PlayerRef playerA = player("ManifestA");
        PlayerRef playerB = player("ManifestB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());

        CompetitiveExecutionSnapshot execution = executions.assign(
                UUID.randomUUID(),
                CompetitiveActivityKind.RANKED_ARENA,
                match.matchId(),
                BACKEND,
                LEASE
        );
        CompetitiveRuntimeManifest manifest = manifests.load(execution.executionId()).orElseThrow();

        assertEquals(CompetitiveActivityKind.RANKED_ARENA, manifest.activityKind());
        assertEquals(match.matchId(), manifest.activityId());
        assertEquals("arena.legacy_1_8_9", manifest.rulesetId());
        assertEquals(1, manifest.rulesetVersion());
        assertEquals(1, manifest.teamSize());
        assertEquals(2, manifest.participants().size());

        CompetitiveRuntimeParticipant a = manifest.participants().get(0);
        CompetitiveRuntimeParticipant b = manifest.participants().get(1);
        assertEquals("A", a.sideKey());
        assertEquals(playerA.playerId(), a.sideId());
        assertEquals(playerA.playerId(), a.playerId());
        assertEquals(playerA.minecraftUuid(), a.minecraftUuid());
        assertEquals("ManifestA", a.playerName());
        assertEquals("B", b.sideKey());
        assertEquals(playerB.playerId(), b.sideId());
        assertEquals(playerB.minecraftUuid(), b.minecraftUuid());
    }

    @Test
    void clanWarAssignmentFreezesRosterSidesWithoutPersistentItems() throws Exception {
        PlayerRef challenger = player("ManifestCA");
        PlayerRef defender = player("ManifestDB");
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), "Manifest CA", randomTag()
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), "Manifest DB", randomTag()
        );
        ClanWarSnapshot war = wars.challenge(
                UUID.randomUUID(),
                challenger.playerId(),
                challengerClan.clanId(),
                defenderClan.clanId()
        );
        wars.accept(UUID.randomUUID(), war.warId(), defender.playerId());
        wars.setRoster(
                UUID.randomUUID(), war.warId(), challenger.playerId(), challengerClan.clanId(), List.of(challenger.playerId())
        );
        wars.setRoster(
                UUID.randomUUID(), war.warId(), defender.playerId(), defenderClan.clanId(), List.of(defender.playerId())
        );
        wars.lockRoster(UUID.randomUUID(), war.warId());

        CompetitiveExecutionSnapshot execution = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.CLAN_WAR, war.warId(), BACKEND, LEASE
        );
        CompetitiveRuntimeManifest manifest = manifests.load(execution.executionId()).orElseThrow();

        assertEquals("war.legacy_1_8_9", manifest.rulesetId());
        assertEquals(1, manifest.teamSize());
        assertEquals(2, manifest.participants().size());
        CompetitiveRuntimeParticipant first = manifest.participants().get(0);
        CompetitiveRuntimeParticipant second = manifest.participants().get(1);
        assertEquals("CHALLENGER", first.sideKey());
        assertEquals(challengerClan.clanId(), first.sideId());
        assertEquals(challenger.minecraftUuid(), first.minecraftUuid());
        assertEquals("DEFENDER", second.sideKey());
        assertEquals(defenderClan.clanId(), second.sideId());
        assertEquals(defender.minecraftUuid(), second.minecraftUuid());
    }

    @Test
    void materializedRuntimeManifestIsImmutable() throws Exception {
        PlayerRef playerA = player("ImmutableA");
        PlayerRef playerB = player("ImmutableB");
        RankedMatchSnapshot match = ranked.createMatch(UUID.randomUUID(), playerA.playerId(), playerB.playerId());
        CompetitiveExecutionSnapshot execution = executions.assign(
                UUID.randomUUID(), CompetitiveActivityKind.RANKED_ARENA, match.matchId(), BACKEND, LEASE
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement updateSpec = connection.prepareStatement("""
                     UPDATE competitive_execution_specs
                     SET team_size = 2
                     WHERE execution_id = ?
                     """)) {
            updateSpec.setObject(1, execution.executionId());
            assertThrows(SQLException.class, updateSpec::executeUpdate);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement updateParticipant = connection.prepareStatement("""
                     UPDATE competitive_execution_participants
                     SET side_key = 'B'
                     WHERE execution_id = ? AND participant_index = 0
                     """)) {
            updateParticipant.setObject(1, execution.executionId());
            assertThrows(SQLException.class, updateParticipant::executeUpdate);
        }
    }

    @Test
    void runtimeManifestSchemaContainsNoPersistentValueSurface() throws SQLException {
        Set<String> forbiddenFragments = Set.of(
                "item", "inventory", "custody", "coin", "wallet", "balance", "commodity", "bank", "auction", "bazaar"
        );
        for (String table : List.of("competitive_execution_specs", "competitive_execution_participants")) {
            List<String> columns = columns(table);
            assertFalse(columns.isEmpty());
            for (String column : columns) {
                String normalized = column.toLowerCase(Locale.ROOT);
                assertTrue(
                        forbiddenFragments.stream().noneMatch(normalized::contains),
                        () -> "legacy runtime manifest leaked persistent-value column " + table + "." + column
                );
            }
        }
    }

    private List<String> columns(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT column_name
                     FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = ?
                     ORDER BY ordinal_position
                     """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<String> result = new ArrayList<>();
                while (rows.next()) result.add(rows.getString("column_name"));
                return List.copyOf(result);
            }
        }
    }

    private PlayerRef player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        return new PlayerRef(identities.ensurePlayer(minecraftUuid, name), minecraftUuid);
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

    private record PlayerRef(UUID playerId, UUID minecraftUuid) { }
}
