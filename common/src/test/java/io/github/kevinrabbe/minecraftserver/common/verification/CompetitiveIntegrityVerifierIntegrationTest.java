package io.github.kevinrabbe.minecraftserver.common.verification;

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
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarCompletionResult;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarLifecycleRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarResolutionRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarRuleset;
import io.github.kevinrabbe.minecraftserver.common.pvp.ClanWarSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedArenaRepository;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedArenaRuleset;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedMatchResult;
import io.github.kevinrabbe.minecraftserver.common.pvp.RankedMatchSnapshot;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CompetitiveIntegrityVerifierIntegrationTest {
    private static final String WAR_ITEM = "verify.war_sword";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private RankedArenaRepository arena;
    private ClanWarLifecycleRepository wars;
    private ClanWarResolutionRepository warResolutions;
    private UniqueItemAuthorityRepository items;
    private CompetitiveIntegrityVerifier verifier;

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
        items = new UniqueItemAuthorityRepository(
                dataSource,
                new ItemCatalog(List.of(new ItemDefinition(
                        WAR_ITEM,
                        "IRON_SWORD",
                        "Verifier War Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                )))
        );
        verifier = new CompetitiveIntegrityVerifier(dataSource);
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
                        ranked_match_results,
                        ranked_match_participants,
                        ranked_matches,
                        ranked_ratings,
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
    void healthyRankedAndClanWarAuthorityProducesNoIssues() throws Exception {
        UUID rankedA = player("RankHealthyA");
        UUID rankedB = player("RankHealthyB");
        completeRanked(rankedA, rankedB, rankedA);

        WarFixture war = completedWar("WarHealthyA", "WarHealthyB");
        assertEquals(war.clanA().clanId(), war.result().war().winningClanId());

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void rankedRatingDriftIsReportedAgainstLatestImmutableResult() throws Exception {
        UUID winner = player("RankDriftA");
        UUID loser = player("RankDriftB");
        completeRanked(winner, loser, winner);

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ranked_ratings
                    SET rating = rating + 1,
                        state_version = state_version + 1,
                        updated_at = NOW()
                    WHERE player_id = ?
                    """)) {
                statement.setObject(1, winner);
                assertEquals(1, statement.executeUpdate());
            }
        });

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("RANKED_RATING_HEAD_MISMATCH")
                        && issue.subjectId().equals(winner.toString())));
    }

    @Test
    void rankedParticipantReleaseDriftIsReported() throws Exception {
        UUID winner = player("RankPartA");
        UUID loser = player("RankPartB");
        RankedMatchResult result = completeRanked(winner, loser, winner);

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ranked_match_participants
                    SET released_at = NULL
                    WHERE match_id = ? AND player_id = ?
                    """)) {
                statement.setObject(1, result.match().matchId());
                statement.setObject(2, winner);
                assertEquals(1, statement.executeUpdate());
            }
        });

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("RANKED_PARTICIPANT_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(result.match().matchId().toString())));
    }

    @Test
    void missingRankedResultIsReported() throws Exception {
        UUID winner = player("RankResultA");
        UUID loser = player("RankResultB");
        RankedMatchResult result = completeRanked(winner, loser, winner);

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM ranked_match_results WHERE match_id = ?"
            )) {
                statement.setObject(1, result.match().matchId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("RANKED_RESULT_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(result.match().matchId().toString())));
    }

    @Test
    void clanWarRatingDriftIsReportedAgainstLatestImmutableResult() throws Exception {
        WarFixture fixture = completedWar("WarDriftA", "WarDriftB");

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE clan_war_ratings
                    SET rating = rating + 1,
                        state_version = state_version + 1,
                        updated_at = NOW()
                    WHERE clan_id = ?
                    """)) {
                statement.setObject(1, fixture.clanA().clanId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("CLAN_WAR_RATING_HEAD_MISMATCH")
                        && issue.subjectId().equals(fixture.clanA().clanId().toString())));
    }

    @Test
    void terminalClanWarRosterDriftIsReported() throws Exception {
        WarFixture fixture = completedWar("WarRosterA", "WarRosterB");

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE clan_war_rosters
                    SET released_at = NULL
                    WHERE war_id = ? AND player_id = ?
                    """)) {
                statement.setObject(1, fixture.result().war().warId());
                statement.setObject(2, fixture.leaderA());
                assertEquals(1, statement.executeUpdate());
            }
        });

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("CLAN_WAR_ROSTER_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(fixture.result().war().warId().toString())));
    }

    @Test
    void orphanWarCustodyIsReported() throws Exception {
        UUID leaderA = player("WarCustodyA");
        UUID leaderB = player("WarCustodyB");
        ClanSnapshot clanA = memberships.createClan(UUID.randomUUID(), leaderA, "Custody A", "CDA");
        ClanSnapshot clanB = memberships.createClan(UUID.randomUUID(), leaderB, "Custody B", "CDB");
        ClanWarSnapshot war = wars.challenge(UUID.randomUUID(), leaderA, clanA.clanId(), clanB.clanId());
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), WAR_ITEM, leaderA, "test.item", leaderA
        );

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE item_instances
                    SET location_kind = 'WAR_CUSTODY',
                        location_id = ?,
                        state_version = state_version + 1,
                        updated_at = NOW()
                    WHERE item_instance_id = ?
                    """)) {
                statement.setObject(1, war.warId());
                statement.setObject(2, item.itemInstanceId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        List<IntegrityIssue> issues = verifier.verify(100);
        assertTrue(issues.stream().anyMatch(issue ->
                issue.code().equals("CLAN_WAR_CUSTODY_EVIDENCE_MISMATCH")
                        && issue.subjectId().equals(war.warId() + ":" + item.itemInstanceId())));
    }

    @Test
    void outputIsBoundedAcrossCompetitiveCorruptions() throws Exception {
        UUID firstA = player("BoundRankA");
        UUID firstB = player("BoundRankB");
        UUID secondA = player("BoundRankC");
        UUID secondB = player("BoundRankD");
        completeRanked(firstA, firstB, firstA);
        completeRanked(secondA, secondB, secondA);

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ranked_ratings
                    SET rating = rating + 1,
                        state_version = state_version + 1,
                        updated_at = NOW()
                    WHERE player_id IN (?, ?)
                    """)) {
                statement.setObject(1, firstA);
                statement.setObject(2, secondA);
                assertEquals(2, statement.executeUpdate());
            }
        });

        assertEquals(1, verifier.verify(1).size());
    }

    private RankedMatchResult completeRanked(UUID playerA, UUID playerB, UUID winner) throws SQLException {
        RankedMatchSnapshot match = arena.createMatch(UUID.randomUUID(), playerA, playerB);
        arena.startMatch(UUID.randomUUID(), match.matchId());
        return arena.completeMatch(UUID.randomUUID(), match.matchId(), winner);
    }

    private WarFixture completedWar(String nameA, String nameB) throws SQLException {
        UUID leaderA = player(nameA);
        UUID leaderB = player(nameB);
        ClanSnapshot clanA = memberships.createClan(UUID.randomUUID(), leaderA, nameA + " Clan", randomTag());
        ClanSnapshot clanB = memberships.createClan(UUID.randomUUID(), leaderB, nameB + " Clan", randomTag());
        ClanWarSnapshot war = wars.challenge(UUID.randomUUID(), leaderA, clanA.clanId(), clanB.clanId());
        wars.accept(UUID.randomUUID(), war.warId(), leaderB);
        wars.setRoster(UUID.randomUUID(), war.warId(), leaderA, clanA.clanId(), List.of(leaderA));
        wars.setRoster(UUID.randomUUID(), war.warId(), leaderB, clanB.clanId(), List.of(leaderB));
        wars.lockRoster(UUID.randomUUID(), war.warId());
        wars.start(UUID.randomUUID(), war.warId());
        ClanWarCompletionResult result = warResolutions.complete(UUID.randomUUID(), war.warId(), clanA.clanId());
        return new WarFixture(leaderA, leaderB, clanA, clanB, result);
    }

    private void withReplicationTriggersDisabled(SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
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

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    private record WarFixture(
            UUID leaderA,
            UUID leaderB,
            ClanSnapshot clanA,
            ClanSnapshot clanB,
            ClanWarCompletionResult result
    ) { }
}
