package io.github.kevinrabbe.minecraftserver.common.clan;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanTreasuryRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;
    private ClanMembershipRepository memberships;
    private ClanRoleRepository roles;
    private ClanTreasuryRepository treasury;

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
        wallets = new CoinWalletRepository(dataSource);
        memberships = new ClanMembershipRepository(dataSource);
        roles = new ClanRoleRepository(dataSource);
        treasury = new ClanTreasuryRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
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

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void anyMemberMayDepositAndExactRetryCannotChargeTwice() throws Exception {
        UUID leader = fundedPlayer("TreasLead", 2_000);
        UUID member = fundedPlayer("TreasMember", 1_000);
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "Treasury", "TRY");
        join(clan, leader, member);
        UUID operationId = UUID.randomUUID();

        ClanTreasuryTransferResult first = treasury.deposit(
                operationId, clan.clanId(), member, 400, "clan.treasury_deposit"
        );
        ClanTreasuryTransferResult retry = treasury.deposit(
                operationId, clan.clanId(), member, 400, "clan.treasury_deposit"
        );

        assertEquals(first, retry);
        assertEquals(400L, first.treasury().balanceMinor());
        assertEquals(600L, wallets.load(member).balanceMinor());
        assertEquals(400L, clanLedgerNet(clan.clanId()));
        assertEquals(600L, playerCoinLedgerNet(member));
    }

    @Test
    void memberCannotWithdrawButOfficerAndLeaderCan() throws Exception {
        UUID leader = fundedPlayer("WithLead", 2_000);
        UUID member = fundedPlayer("WithMember", 0);
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "Withdraw", "WTH");
        join(clan, leader, member);
        treasury.deposit(UUID.randomUUID(), clan.clanId(), leader, 1_000, "clan.treasury_deposit");

        assertThrows(
                ClanAssetException.class,
                () -> treasury.withdraw(
                        UUID.randomUUID(), clan.clanId(), member, 100, "clan.treasury_withdraw"
                )
        );

        roles.setMemberRole(UUID.randomUUID(), clan.clanId(), leader, member, ClanRole.OFFICER);
        ClanTreasuryTransferResult officerWithdraw = treasury.withdraw(
                UUID.randomUUID(), clan.clanId(), member, 250, "clan.treasury_withdraw"
        );
        assertEquals(750L, officerWithdraw.treasury().balanceMinor());
        assertEquals(250L, wallets.load(member).balanceMinor());

        ClanTreasuryTransferResult leaderWithdraw = treasury.withdraw(
                UUID.randomUUID(), clan.clanId(), leader, 100, "clan.treasury_withdraw"
        );
        assertEquals(650L, leaderWithdraw.treasury().balanceMinor());
        assertEquals(1_100L, wallets.load(leader).balanceMinor());
        assertEquals(650L, clanLedgerNet(clan.clanId()));
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawClanTreasury() throws Exception {
        UUID leader = fundedPlayer("RaceTreasLead", 2_000);
        UUID officer = fundedPlayer("RaceTreasOff", 0);
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "RaceTreas", "RTR");
        join(clan, leader, officer);
        roles.setMemberRole(UUID.randomUUID(), clan.clanId(), leader, officer, ClanRole.OFFICER);
        treasury.deposit(UUID.randomUUID(), clan.clanId(), leader, 1_000, "clan.treasury_deposit");

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ClanTreasuryTransferResult> first = executor.submit(() -> treasury.withdraw(
                    UUID.randomUUID(), clan.clanId(), leader, 800, "clan.treasury_withdraw"
            ));
            Future<ClanTreasuryTransferResult> second = executor.submit(() -> treasury.withdraw(
                    UUID.randomUUID(), clan.clanId(), officer, 800, "clan.treasury_withdraw"
            ));
            for (Future<ClanTreasuryTransferResult> future : List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof ClanAssetException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(200L, treasury.load(clan.clanId()).balanceMinor());
        assertEquals(200L, clanLedgerNet(clan.clanId()));
    }

    @Test
    void operationIdCannotBeReboundToDifferentTreasuryTransfer() throws Exception {
        UUID leader = fundedPlayer("BindTreasLead", 1_000);
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "BindTreas", "BTR");
        UUID operationId = UUID.randomUUID();
        treasury.deposit(operationId, clan.clanId(), leader, 100, "clan.treasury_deposit");

        assertThrows(
                ClanAssetException.class,
                () -> treasury.deposit(operationId, clan.clanId(), leader, 200, "clan.treasury_deposit")
        );
        assertEquals(100L, treasury.load(clan.clanId()).balanceMinor());
    }

    private UUID fundedPlayer(String name, long amount) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        if (amount > 0) {
            wallets.creditFromSystem(UUID.randomUUID(), playerId, amount, "test.funding");
        }
        return playerId;
    }

    private void join(ClanSnapshot clan, UUID leader, UUID player) throws SQLException {
        ClanInvitationSnapshot invite = memberships.invite(
                UUID.randomUUID(), clan.clanId(), leader, player,
                Instant.now().plus(1, ChronoUnit.DAYS)
        );
        memberships.acceptInvite(UUID.randomUUID(), invite.inviteId(), player);
    }

    private long clanLedgerNet(UUID clanId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                     FROM economic_ledger
                     WHERE player_id IS NULL
                       AND asset_type = 'CURRENCY'
                       AND asset_id = 'coin'
                       AND related_entity_id = ?
                     """)) {
            statement.setString(1, clanId.toString());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long playerCoinLedgerNet(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                     FROM economic_ledger
                     WHERE player_id = ? AND asset_type = 'CURRENCY' AND asset_id = 'coin'
                     """)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
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
