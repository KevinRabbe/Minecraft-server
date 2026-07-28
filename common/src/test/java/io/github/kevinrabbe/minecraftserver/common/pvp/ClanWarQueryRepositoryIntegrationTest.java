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
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanWarQueryRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private ClanWarLifecycleRepository wars;
    private ClanWarResolutionRepository resolutions;
    private ClanWarQueryRepository queries;

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
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        resolutions = new ClanWarResolutionRepository(dataSource);
        queries = new ClanWarQueryRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
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
    void tagResolutionAndOpenWarProjectionExposeRosterProgressWithoutCustodyState() throws Exception {
        Player challenger = player("WarQueryA");
        Player defender = player("WarQueryB");
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), "Query Challenger", "QCHA"
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), "Query Defender", "QDEF"
        );

        assertEquals(defenderClan.clanId(), queries.findClanIdByTag("qdef").orElseThrow());
        assertTrue(queries.findClanIdByTag("none").isEmpty());

        ClanWarSnapshot challenged = wars.challenge(
                UUID.randomUUID(),
                challenger.playerId(),
                challengerClan.clanId(),
                defenderClan.clanId()
        );
        ClanWarView challengedView = queries.listOpenForClan(defenderClan.clanId(), 10).getFirst();
        assertEquals(challenged.warId(), challengedView.warId());
        assertEquals("QCHA", challengedView.challengerTag());
        assertEquals("QDEF", challengedView.defenderTag());
        assertEquals(ClanWarStatus.CHALLENGED, challengedView.status());
        assertEquals(1, challengedView.teamSize());
        assertEquals(0, challengedView.challengerRosterCount());
        assertEquals(0, challengedView.defenderRosterCount());
        assertEquals("war.legacy_1_8_9", challengedView.rulesetId());
        assertEquals(1, challengedView.rulesetVersion());

        wars.accept(UUID.randomUUID(), challenged.warId(), defender.playerId());
        wars.setRoster(
                UUID.randomUUID(), challenged.warId(), challenger.playerId(), challengerClan.clanId(),
                List.of(challenger.playerId())
        );

        ClanWarView acceptedView = queries.load(challenged.warId()).orElseThrow();
        assertEquals(ClanWarStatus.ACCEPTED, acceptedView.status());
        assertEquals(1, acceptedView.challengerRosterCount());
        assertEquals(0, acceptedView.defenderRosterCount());
        assertEquals(1, queries.listOpenForClan(challengerClan.clanId(), 10).size());
        assertEquals(1, queries.listOpenForClan(defenderClan.clanId(), 10).size());
    }

    @Test
    void terminalWarsDisappearFromOpenProjection() throws Exception {
        Player challenger = player("WarTermA");
        Player defender = player("WarTermB");
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), "Terminal Challenger", "TCHA"
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), "Terminal Defender", "TDEF"
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
        wars.lockRoster(UUID.randomUUID(), war.warId());
        wars.start(UUID.randomUUID(), war.warId());

        assertEquals(1, queries.listOpenForClan(challengerClan.clanId(), 10).size());
        resolutions.fail(UUID.randomUUID(), war.warId());
        assertTrue(queries.listOpenForClan(challengerClan.clanId(), 10).isEmpty());
        assertEquals(ClanWarStatus.FAILED, queries.load(war.warId()).orElseThrow().status());
    }

    @Test
    void openWarLimitIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> queries.listOpenForClan(UUID.randomUUID(), 0));
        assertThrows(IllegalArgumentException.class, () -> queries.listOpenForClan(UUID.randomUUID(), 101));
    }

    private Player player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        return new Player(identities.ensurePlayer(minecraftUuid, name), minecraftUuid);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record Player(UUID playerId, UUID minecraftUuid) {
    }
}
