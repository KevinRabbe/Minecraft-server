package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.clan.ClanMembershipRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanSnapshot;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanTreasuryRepository;
import io.github.kevinrabbe.minecraftserver.common.clan.ClanTreasurySnapshot;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
class ClanTreasuryIntegrityVerifierIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;
    private ClanMembershipRepository memberships;
    private ClanTreasuryRepository treasury;
    private ClanTreasuryIntegrityVerifier verifier;

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
        wallets = new CoinWalletRepository(dataSource);
        memberships = new ClanMembershipRepository(dataSource);
        treasury = new ClanTreasuryRepository(dataSource);
        verifier = new ClanTreasuryIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        truncateAuthority();
    }

    @AfterEach
    void cleanDatabase() throws SQLException {
        truncateAuthority();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void realDepositWithdrawDepositHistoryRemainsClean() throws Exception {
        UUID leader = fundedPlayer("TreasIntLead", 5_000L);
        ClanSnapshot clan = createClan(leader, "Treas Int", "TIN");

        treasury.deposit(
                UUID.randomUUID(), clan.clanId(), leader, 1_000L, "clan.integrity_deposit"
        );
        treasury.withdraw(
                UUID.randomUUID(), clan.clanId(), leader, 250L, "clan.integrity_withdraw"
        );
        treasury.deposit(
                UUID.randomUUID(), clan.clanId(), leader, 100L, "clan.integrity_deposit"
        );

        ClanTreasurySnapshot snapshot = treasury.load(clan.clanId());
        assertEquals(850L, snapshot.balanceMinor());
        assertEquals(3L, snapshot.stateVersion());
        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void malformedOrphanTransferResultIsOperationEvidenceMismatchOnly() throws Exception {
        UUID player = fundedPlayer("TreasBadOp", 1_000L);
        UUID operationId = UUID.randomUUID();
        UUID missingClanId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO processed_operations(operation_id, operation_type, result)
                     VALUES (?, 'CLAN_TREASURY_DEPOSIT', ?::jsonb)
                     """)) {
            statement.setObject(1, operationId);
            statement.setString(2, """
                    {"clan_id":"%s","player_id":"%s","amount_minor":1,"reason":"test.malformed_treasury"}
                    """.formatted(missingClanId, player).trim());
            assertEquals(1, statement.executeUpdate());
        }

        assertContainsOnly("CLAN_TREASURY_OPERATION_EVIDENCE_MISMATCH", operationId.toString());
    }

    @Test
    void mutableTreasuryVersionDriftIsStateEvidenceMismatchOnly() throws Exception {
        UUID leader = fundedPlayer("TreasState", 2_000L);
        ClanSnapshot clan = createClan(leader, "Treas State", "TST");
        treasury.deposit(
                UUID.randomUUID(), clan.clanId(), leader, 500L, "clan.integrity_deposit"
        );

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_treasuries
                     SET state_version = state_version + 1
                     WHERE clan_id = ?
                     """)) {
            statement.setObject(1, clan.clanId());
            assertEquals(1, statement.executeUpdate());
        }

        assertContainsOnly("CLAN_TREASURY_STATE_EVIDENCE_MISMATCH", clan.clanId().toString());
    }

    @Test
    void unexplainedClanSideCoinLedgerDriftIsStateEvidenceMismatchOnly() throws Exception {
        UUID leader = fundedPlayer("TreasLedgerNet", 1_000L);
        ClanSnapshot clan = createClan(leader, "Treas Ledger", "TLG");

        insertLedger(
                UUID.randomUUID(),
                0,
                null,
                1L,
                "CREDIT",
                "test.unexplained_clan_coin",
                clan.clanId().toString()
        );

        assertContainsOnly("CLAN_TREASURY_STATE_EVIDENCE_MISMATCH", clan.clanId().toString());
    }

    @Test
    void extraTransferLedgerLineIsLedgerEvidenceMismatchOnly() throws Exception {
        UUID leader = fundedPlayer("TreasExtraLine", 2_000L);
        ClanSnapshot clan = createClan(leader, "Treas Extra", "TEX");
        UUID operationId = UUID.randomUUID();
        treasury.deposit(
                operationId, clan.clanId(), leader, 500L, "clan.integrity_deposit"
        );

        insertLedger(
                operationId,
                2,
                leader,
                1L,
                "CREDIT",
                "test.extra_treasury_line",
                null
        );

        assertContainsOnly("CLAN_TREASURY_LEDGER_EVIDENCE_MISMATCH", operationId.toString());
    }

    private UUID fundedPlayer(String name, long amountMinor) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        if (amountMinor > 0) {
            wallets.creditFromSystem(UUID.randomUUID(), playerId, amountMinor, "test.treasury_integrity_funding");
        }
        return playerId;
    }

    private ClanSnapshot createClan(UUID leader, String name, String tag) throws SQLException {
        return memberships.createClan(UUID.randomUUID(), leader, name, tag);
    }

    private void insertLedger(
            UUID operationId,
            int lineNo,
            UUID playerId,
            long amountMinor,
            String direction,
            String reason,
            String relatedEntityId
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO economic_ledger(
                         operation_id, line_no, player_id, asset_type, asset_id,
                         amount, direction, reason, related_entity_id
                     ) VALUES (?, ?, ?, 'CURRENCY', 'coin', ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            if (playerId == null) {
                statement.setNull(3, java.sql.Types.OTHER);
            } else {
                statement.setObject(3, playerId);
            }
            statement.setLong(4, amountMinor);
            statement.setString(5, direction);
            statement.setString(6, reason);
            if (relatedEntityId == null) {
                statement.setNull(7, java.sql.Types.VARCHAR);
            } else {
                statement.setString(7, relatedEntityId);
            }
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void assertContainsOnly(String expectedCode, String expectedSubject) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals(expectedCode, issue.code());
        assertEquals(expectedSubject, issue.subjectId());
    }

    private void truncateAuthority() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        clan_invitations,
                        clan_commodity_balances,
                        clan_treasuries,
                        clan_members,
                        clans,
                        economic_ledger,
                        processed_operations,
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
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
