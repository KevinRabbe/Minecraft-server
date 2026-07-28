package io.github.kevinrabbe.minecraftserver.common.architecture;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanCompetitiveSchemaInvariantTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                4
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE clans, players RESTART IDENTITY CASCADE");
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void newClanAutomaticallyGetsZeroBalanceTreasury() throws SQLException {
        UUID leaderId = identities.ensurePlayer(UUID.randomUUID(), "ClanLeader");
        UUID clanId = createClan(leaderId, "Builders", "BLD");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT balance_minor, state_version
                     FROM clan_treasuries
                     WHERE clan_id = ?
                     """)) {
            statement.setObject(1, clanId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertEquals(0L, result.getLong("balance_minor"));
                assertEquals(0L, result.getLong("state_version"));
            }
        }
    }

    @Test
    void clanCannotHaveTwoLeaders() throws SQLException {
        UUID first = identities.ensurePlayer(UUID.randomUUID(), "FirstLeader");
        UUID second = identities.ensurePlayer(UUID.randomUUID(), "SecondLeader");
        UUID clanId = createClan(first, "OneLeader", "ONE");

        assertThrows(SQLException.class, () -> addMember(clanId, second, "LEADER"));
    }

    @Test
    void warRosterRejectsPlayerWhoIsNotMemberOfDeclaredClan() throws SQLException {
        UUID challengerLeader = identities.ensurePlayer(UUID.randomUUID(), "Challenger");
        UUID defenderLeader = identities.ensurePlayer(UUID.randomUUID(), "Defender");
        UUID outsider = identities.ensurePlayer(UUID.randomUUID(), "Outsider");
        UUID challengerClan = createClan(challengerLeader, "Challengers", "CHA");
        UUID defenderClan = createClan(defenderLeader, "Defenders", "DEF");
        UUID warId = createWar(challengerClan, defenderClan);

        assertThrows(SQLException.class, () -> addWarRosterMember(warId, challengerClan, outsider));
    }

    private UUID createClan(UUID creatorId, String name, String tag) throws SQLException {
        UUID clanId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement clan = connection.prepareStatement("""
                    INSERT INTO clans(clan_id, name, tag, created_by_player_id)
                    VALUES (?, ?, ?, ?)
                    """);
                 PreparedStatement leader = connection.prepareStatement("""
                    INSERT INTO clan_members(clan_id, player_id, role)
                    VALUES (?, ?, 'LEADER')
                    """)) {
                clan.setObject(1, clanId);
                clan.setString(2, name);
                clan.setString(3, tag);
                clan.setObject(4, creatorId);
                clan.executeUpdate();

                leader.setObject(1, clanId);
                leader.setObject(2, creatorId);
                leader.executeUpdate();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return clanId;
    }

    private void addMember(UUID clanId, UUID playerId, String role) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO clan_members(clan_id, player_id, role)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            statement.setString(3, role);
            statement.executeUpdate();
        }
    }

    private UUID createWar(UUID challengerClan, UUID defenderClan) throws SQLException {
        UUID warId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO clan_wars(war_id, challenger_clan_id, defender_clan_id, status)
                     VALUES (?, ?, ?, 'CHALLENGED')
                     """)) {
            statement.setObject(1, warId);
            statement.setObject(2, challengerClan);
            statement.setObject(3, defenderClan);
            statement.executeUpdate();
        }
        return warId;
    }

    private void addWarRosterMember(UUID warId, UUID clanId, UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO clan_war_rosters(war_id, clan_id, player_id)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setObject(1, warId);
            statement.setObject(2, clanId);
            statement.setObject(3, playerId);
            statement.executeUpdate();
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
