package io.github.kevinrabbe.minecraftserver.common.verification;

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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanIntegrityVerifierIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository clans;
    private ClanIntegrityVerifier verifier;

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
        clans = new ClanMembershipRepository(dataSource);
        verifier = new ClanIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        clan_chat_messages,
                        clan_invitations,
                        clan_commodity_balances,
                        clan_treasuries,
                        clan_member_counts,
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
    void healthyClanCountersProduceNoIssuesAndAggregateVerifierIncludesClanCheck() throws Exception {
        UUID leader = identities.ensurePlayer(UUID.randomUUID(), "VerifyHealthy");
        clans.createClan(UUID.randomUUID(), leader, "Healthy Clan", "HCL");

        assertTrue(verifier.verify(10).isEmpty());
        assertTrue(new PersistentIntegrityVerifier(dataSource).verify(10).isEmpty());
    }

    @Test
    void wrongTrackedCountIsReportedAsCriticalMismatch() throws Exception {
        ClanSnapshot clan = createClan("VerifyClanWrong", "Wrong Clan", "WCL");
        setTrackedCount(clan.clanId(), 7);

        List<IntegrityIssue> issues = verifier.verify(10);

        assertEquals(1, issues.size());
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("CLAN_MEMBER_COUNT_MISMATCH", issue.code());
        assertEquals(clan.clanId().toString(), issue.subjectId());
        assertTrue(issue.message().contains("actual=1"));
        assertTrue(issue.message().contains("tracked=7"));
    }

    @Test
    void missingCounterRowIsReportedAsCriticalMismatch() throws Exception {
        ClanSnapshot clan = createClan("VerifyMissing", "Missing Clan", "MCL");
        deleteTrackedCount(clan.clanId());

        List<IntegrityIssue> issues = verifier.verify(10);

        assertEquals(1, issues.size());
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("CLAN_MEMBER_COUNT_MISMATCH", issue.code());
        assertEquals(clan.clanId().toString(), issue.subjectId());
        assertTrue(issue.message().contains("tracked=missing"));
    }

    private ClanSnapshot createClan(String leaderName, String clanName, String clanTag) throws SQLException {
        UUID leader = identities.ensurePlayer(UUID.randomUUID(), leaderName);
        return clans.createClan(UUID.randomUUID(), leader, clanName, clanTag);
    }

    private void setTrackedCount(UUID clanId, int count) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_member_counts
                     SET member_count = ?
                     WHERE clan_id = ?
                     """)) {
            statement.setInt(1, count);
            statement.setObject(2, clanId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void deleteTrackedCount(UUID clanId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM clan_member_counts
                     WHERE clan_id = ?
                     """)) {
            statement.setObject(1, clanId);
            assertEquals(1, statement.executeUpdate());
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
