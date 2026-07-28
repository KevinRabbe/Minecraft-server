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
class ClanWarPreparationRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private ClanWarLifecycleRepository wars;
    private ClanWarPreparationRepository preparation;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                6
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        memberships = new ClanMembershipRepository(dataSource);
        wars = new ClanWarLifecycleRepository(dataSource, ClanWarRuleset.legacy189V1());
        preparation = new ClanWarPreparationRepository(dataSource);
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
    void onlyAcceptedWarsWithBothExactRostersAreLockCandidates() throws Exception {
        WarFixture complete = acceptedWar("PrepA", "PrepB");
        WarFixture incomplete = acceptedWar("PrepC", "PrepD");

        wars.setRoster(
                UUID.randomUUID(), complete.war().warId(), complete.challenger().playerId(),
                complete.challengerClan().clanId(), List.of(complete.challenger().playerId())
        );
        assertTrue(preparation.listRosterLockReady(10).isEmpty());

        wars.setRoster(
                UUID.randomUUID(), complete.war().warId(), complete.defender().playerId(),
                complete.defenderClan().clanId(), List.of(complete.defender().playerId())
        );
        assertEquals(List.of(complete.war().warId()), preparation.listRosterLockReady(10));

        wars.setRoster(
                UUID.randomUUID(), incomplete.war().warId(), incomplete.challenger().playerId(),
                incomplete.challengerClan().clanId(), List.of(incomplete.challenger().playerId())
        );
        assertEquals(List.of(complete.war().warId()), preparation.listRosterLockReady(10));

        wars.lockRoster(UUID.randomUUID(), complete.war().warId());
        assertTrue(preparation.listRosterLockReady(10).isEmpty());
    }

    @Test
    void candidateLimitIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> preparation.listRosterLockReady(0));
        assertThrows(IllegalArgumentException.class, () -> preparation.listRosterLockReady(501));
    }

    private WarFixture acceptedWar(String challengerName, String defenderName) throws SQLException {
        Player challenger = player(challengerName);
        Player defender = player(defenderName);
        ClanSnapshot challengerClan = memberships.createClan(
                UUID.randomUUID(), challenger.playerId(), challengerName + " Clan", randomTag()
        );
        ClanSnapshot defenderClan = memberships.createClan(
                UUID.randomUUID(), defender.playerId(), defenderName + " Clan", randomTag()
        );
        ClanWarSnapshot challenged = wars.challenge(
                UUID.randomUUID(), challenger.playerId(), challengerClan.clanId(), defenderClan.clanId()
        );
        ClanWarSnapshot accepted = wars.accept(UUID.randomUUID(), challenged.warId(), defender.playerId());
        return new WarFixture(accepted, challengerClan, defenderClan, challenger, defender);
    }

    private Player player(String name) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        return new Player(identities.ensurePlayer(minecraftUuid, name), minecraftUuid);
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

    private record Player(UUID playerId, UUID minecraftUuid) { }

    private record WarFixture(
            ClanWarSnapshot war,
            ClanSnapshot challengerClan,
            ClanSnapshot defenderClan,
            Player challenger,
            Player defender
    ) { }
}
