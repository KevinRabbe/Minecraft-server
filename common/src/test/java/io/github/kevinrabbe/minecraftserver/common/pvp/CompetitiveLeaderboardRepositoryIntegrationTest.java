package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveLeaderboardRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private RankedArenaRepository arena;
    private ClanWarLifecycleRepository wars;
    private ClanWarResolutionRepository warResolutions;
    private RankedArenaLeaderboardRepository rankedLeaderboard;
    private ClanWarLeaderboardRepository warLeaderboard;

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
        arena = new RankedArenaRepository(dataSource, RankedArenaRuleset.legacy189V1());
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        warResolutions = new ClanWarResolutionRepository(dataSource);
        rankedLeaderboard = new RankedArenaLeaderboardRepository(dataSource);
        warLeaderboard = new ClanWarLeaderboardRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        clan_war_results,
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
    void rankedArenaLeaderboardUsesOnlyAuthoritative189Results() throws SQLException {
        UUID a = player("RankA");
        UUID b = player("RankB");
        UUID c = player("RankC");

        completeRanked(a, b, a);
        completeRanked(a, c, a);

        List<RankedArenaLeaderboardEntry> entries = rankedLeaderboard.top(3);
        assertEquals(3, entries.size());
        assertEquals(a, entries.get(0).playerId());
        assertEquals("RankA", entries.get(0).playerName());
        assertEquals(2L, entries.get(0).wins());
        assertEquals(0L, entries.get(0).losses());
        assertEquals(1, entries.get(0).rank());
        assertEquals(2L, entries.get(1).losses() + entries.get(2).losses());
    }

    @Test
    void clanWarLeaderboardUsesOnlyAuthoritativeWarResults() throws SQLException {
        UUID leaderA = player("WarBoardA");
        UUID leaderB = player("WarBoardB");
        ClanSnapshot clanA = memberships.createClan(UUID.randomUUID(), leaderA, "Board Alpha", "BA");
        ClanSnapshot clanB = memberships.createClan(UUID.randomUUID(), leaderB, "Board Beta", "BB");

        ClanWarSnapshot war = wars.challenge(UUID.randomUUID(), leaderA, clanA.clanId(), clanB.clanId());
        wars.accept(UUID.randomUUID(), war.warId(), leaderB);
        wars.setRoster(UUID.randomUUID(), war.warId(), leaderA, clanA.clanId(), List.of(leaderA));
        wars.setRoster(UUID.randomUUID(), war.warId(), leaderB, clanB.clanId(), List.of(leaderB));
        wars.lockRoster(UUID.randomUUID(), war.warId());
        wars.start(UUID.randomUUID(), war.warId());
        warResolutions.complete(UUID.randomUUID(), war.warId(), clanA.clanId());

        List<ClanWarLeaderboardEntry> entries = warLeaderboard.top(2);
        assertEquals(2, entries.size());
        assertEquals(clanA.clanId(), entries.get(0).clanId());
        assertEquals("Board Alpha", entries.get(0).clanName());
        assertEquals("BA", entries.get(0).clanTag());
        assertEquals(1L, entries.get(0).wins());
        assertEquals(0L, entries.get(0).losses());
        assertEquals(clanB.clanId(), entries.get(1).clanId());
        assertEquals(0L, entries.get(1).wins());
        assertEquals(1L, entries.get(1).losses());
    }

    @Test
    void leaderboardQueriesAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> rankedLeaderboard.top(0));
        assertThrows(IllegalArgumentException.class, () -> warLeaderboard.top(101));
    }

    private void completeRanked(UUID playerA, UUID playerB, UUID winner) throws SQLException {
        RankedMatchSnapshot match = arena.createMatch(UUID.randomUUID(), playerA, playerB);
        arena.startMatch(UUID.randomUUID(), match.matchId());
        arena.completeMatch(UUID.randomUUID(), match.matchId(), winner);
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
