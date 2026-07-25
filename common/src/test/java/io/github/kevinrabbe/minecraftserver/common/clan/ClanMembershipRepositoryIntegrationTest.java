package io.github.kevinrabbe.minecraftserver.common.clan;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanMembershipRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository clans;

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
        clans = new ClanMembershipRepository(dataSource);
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
    void createClanIsExactlyOnceCreatesLeaderAndTreasuryAndNormalizesTag() throws Exception {
        UUID creator = player("ClanCreator");
        UUID operationId = UUID.randomUUID();

        ClanSnapshot first = clans.createClan(operationId, creator, "  Foundry  ", " fn ");
        ClanSnapshot retry = clans.createClan(operationId, creator, "Foundry", "FN");

        assertEquals(first, retry);
        assertEquals("Foundry", first.name());
        assertEquals("FN", first.tag());
        ClanMemberSnapshot leader = clans.loadMember(creator);
        assertEquals(first.clanId(), leader.clanId());
        assertEquals(ClanRole.LEADER, leader.role());
        assertEquals(1L, treasuryCount(first.clanId()));
        assertEquals(1L, tableCount("clans"));
    }

    @Test
    void playerCannotCreateSecondClanWhileAlreadyMember() throws Exception {
        UUID creator = player("OneClanOnly");
        clans.createClan(UUID.randomUUID(), creator, "Alpha", "A");

        assertThrows(
                ClanMembershipException.class,
                () -> clans.createClan(UUID.randomUUID(), creator, "Beta", "B")
        );
        assertEquals(1L, tableCount("clans"));
    }

    @Test
    void leaderAndOfficerCanInviteButMemberCannot() throws Exception {
        UUID leader = player("InviteLeader");
        UUID officer = player("InviteOfficer");
        UUID member = player("InviteMember");
        UUID target = player("InviteTarget");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Guild", "GLD");
        addMember(clan.clanId(), officer, ClanRole.OFFICER);
        addMember(clan.clanId(), member, ClanRole.MEMBER);

        ClanInvitationSnapshot leaderInvite = clans.invite(
                UUID.randomUUID(), clan.clanId(), leader, target, futureExpiry()
        );
        assertEquals(ClanInvitationStatus.PENDING, leaderInvite.status());
        clans.cancelInvite(UUID.randomUUID(), leaderInvite.inviteId(), officer);

        ClanInvitationSnapshot officerInvite = clans.invite(
                UUID.randomUUID(), clan.clanId(), officer, target, futureExpiry()
        );
        assertEquals(officer, officerInvite.invitedByPlayerId());

        UUID otherTarget = player("InviteTarget2");
        assertThrows(
                ClanMembershipException.class,
                () -> clans.invite(UUID.randomUUID(), clan.clanId(), member, otherTarget, futureExpiry())
        );
    }

    @Test
    void acceptedInviteCreatesMemberExactlyOnceAndTerminalInviteCannotBeRewritten() throws Exception {
        UUID leader = player("AcceptLeader");
        UUID invited = player("AcceptTarget");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Acceptors", "ACC");
        ClanInvitationSnapshot invite = clans.invite(
                UUID.randomUUID(), clan.clanId(), leader, invited, futureExpiry()
        );
        UUID acceptOperation = UUID.randomUUID();

        ClanMemberSnapshot first = clans.acceptInvite(acceptOperation, invite.inviteId(), invited);
        ClanMemberSnapshot retry = clans.acceptInvite(acceptOperation, invite.inviteId(), invited);

        assertEquals(first, retry);
        assertEquals(ClanRole.MEMBER, first.role());
        assertEquals(ClanInvitationStatus.ACCEPTED, clans.loadInvitation(invite.inviteId()).status());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_invitations SET status = 'CANCELLED', accepted_at = NULL WHERE invite_id = ?
                     """)) {
            statement.setObject(1, invite.inviteId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
        assertEquals(ClanInvitationStatus.ACCEPTED, clans.loadInvitation(invite.inviteId()).status());
    }

    @Test
    void twoClanInvitesAcceptedConcurrentlyCanCreateOnlyOneMembership() throws Exception {
        UUID leaderA = player("RaceLeaderA");
        UUID leaderB = player("RaceLeaderB");
        UUID target = player("RaceTarget");
        ClanSnapshot clanA = clans.createClan(UUID.randomUUID(), leaderA, "RaceA", "RA");
        ClanSnapshot clanB = clans.createClan(UUID.randomUUID(), leaderB, "RaceB", "RB");
        ClanInvitationSnapshot inviteA = clans.invite(
                UUID.randomUUID(), clanA.clanId(), leaderA, target, futureExpiry()
        );
        ClanInvitationSnapshot inviteB = clans.invite(
                UUID.randomUUID(), clanB.clanId(), leaderB, target, futureExpiry()
        );

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ClanMemberSnapshot> a = executor.submit(
                    () -> clans.acceptInvite(UUID.randomUUID(), inviteA.inviteId(), target)
            );
            Future<ClanMemberSnapshot> b = executor.submit(
                    () -> clans.acceptInvite(UUID.randomUUID(), inviteB.inviteId(), target)
            );
            for (Future<ClanMemberSnapshot> future : List.of(a, b)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof ClanMembershipException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1L, membershipCount(target));
        assertTrue(clans.loadMember(target).clanId().equals(clanA.clanId())
                || clans.loadMember(target).clanId().equals(clanB.clanId()));
    }

    @Test
    void memberAndOfficerMayLeaveButLeaderMustTransferFirst() throws Exception {
        UUID leader = player("LeaveLeader");
        UUID officer = player("LeaveOfficer");
        UUID member = player("LeaveMember");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Leavers", "LEV");
        addMember(clan.clanId(), officer, ClanRole.OFFICER);
        addMember(clan.clanId(), member, ClanRole.MEMBER);

        ClanMembershipRemovalResult memberLeave = clans.leaveClan(UUID.randomUUID(), clan.clanId(), member);
        assertEquals(ClanRole.MEMBER, memberLeave.formerRole());
        ClanMembershipRemovalResult officerLeave = clans.leaveClan(UUID.randomUUID(), clan.clanId(), officer);
        assertEquals(ClanRole.OFFICER, officerLeave.formerRole());
        assertThrows(
                ClanMembershipException.class,
                () -> clans.leaveClan(UUID.randomUUID(), clan.clanId(), leader)
        );
        assertEquals(ClanRole.LEADER, clans.loadMember(leader).role());
    }

    @Test
    void removalPermissionsRespectRoleHierarchy() throws Exception {
        UUID leader = player("RemoveLeader");
        UUID officerA = player("RemoveOffA");
        UUID officerB = player("RemoveOffB");
        UUID member = player("RemoveMember");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Removal", "REM");
        addMember(clan.clanId(), officerA, ClanRole.OFFICER);
        addMember(clan.clanId(), officerB, ClanRole.OFFICER);
        addMember(clan.clanId(), member, ClanRole.MEMBER);

        ClanMembershipRemovalResult removed = clans.removeMember(
                UUID.randomUUID(), clan.clanId(), officerA, member
        );
        assertEquals(ClanRole.MEMBER, removed.formerRole());
        assertThrows(
                ClanMembershipException.class,
                () -> clans.removeMember(UUID.randomUUID(), clan.clanId(), officerA, officerB)
        );
        assertThrows(
                ClanMembershipException.class,
                () -> clans.removeMember(UUID.randomUUID(), clan.clanId(), officerA, leader)
        );

        ClanMembershipRemovalResult officerRemoved = clans.removeMember(
                UUID.randomUUID(), clan.clanId(), leader, officerB
        );
        assertEquals(ClanRole.OFFICER, officerRemoved.formerRole());
    }

    @Test
    void leadershipTransferIsAtomicLeavesExactlyOneLeaderAndRetryReturnsOriginalResult() throws Exception {
        UUID leader = player("TransferLead");
        UUID successor = player("TransferNext");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Transfer", "TRN");
        addMember(clan.clanId(), successor, ClanRole.OFFICER);
        UUID operationId = UUID.randomUUID();

        ClanLeadershipTransferResult first = clans.transferLeadership(
                operationId, clan.clanId(), leader, successor, ClanRole.MEMBER
        );
        ClanLeadershipTransferResult retry = clans.transferLeadership(
                operationId, clan.clanId(), leader, successor, ClanRole.MEMBER
        );

        assertEquals(first, retry);
        assertEquals(ClanRole.LEADER, clans.loadMember(successor).role());
        assertEquals(ClanRole.MEMBER, clans.loadMember(leader).role());
        assertEquals(1L, leaderCount(clan.clanId()));
    }

    @Test
    void directSqlCannotDeleteSoleLeaderOrCreateCommittedClanWithoutLeader() throws Exception {
        UUID leader = player("SqlLeader");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "SqlGuard", "SQL");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM clan_members WHERE clan_id = ? AND player_id = ?"
            )) {
                statement.setObject(1, clan.clanId());
                statement.setObject(2, leader);
                statement.executeUpdate();
            }
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }
        assertEquals(ClanRole.LEADER, clans.loadMember(leader).role());

        UUID creator = player("OrphanCreator");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO clans(clan_id, name, tag, created_by_player_id)
                    VALUES (?, 'Orphan', 'ORP', ?)
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, creator);
                statement.executeUpdate();
            }
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }
    }

    @Test
    void expiredInviteCannotBeAcceptedAndAReplacementCanBeCreated() throws Exception {
        UUID leader = player("ExpireLeader");
        UUID target = player("ExpireTarget");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Expiry", "EXP");
        ClanInvitationSnapshot invite = clans.invite(
                UUID.randomUUID(), clan.clanId(), leader, target, Instant.now().plus(1, ChronoUnit.SECONDS)
        );
        expireInviteDirectly(invite.inviteId());

        assertThrows(
                ClanMembershipException.class,
                () -> clans.acceptInvite(UUID.randomUUID(), invite.inviteId(), target)
        );
        ClanInvitationSnapshot replacement = clans.invite(
                UUID.randomUUID(), clan.clanId(), leader, target, futureExpiry()
        );
        assertNotEquals(invite.inviteId(), replacement.inviteId());
        assertEquals(ClanInvitationStatus.PENDING, replacement.status());
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void addMember(UUID clanId, UUID playerId, ClanRole role) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO clan_members(clan_id, player_id, role) VALUES (?, ?, ?)
                     """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            statement.setString(3, role.name());
            statement.executeUpdate();
        }
    }

    private void expireInviteDirectly(UUID inviteId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_invitations
                     SET status = 'EXPIRED', closed_at = NOW()
                     WHERE invite_id = ?
                     """)) {
            statement.setObject(1, inviteId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private long treasuryCount(UUID clanId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM clan_treasuries WHERE clan_id = ?"
             )) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long membershipCount(UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM clan_members WHERE player_id = ?"
             )) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long leaderCount(UUID clanId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM clan_members WHERE clan_id = ? AND role = 'LEADER'"
             )) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long tableCount(String table) throws SQLException {
        if (!List.of("clans").contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static Instant futureExpiry() {
        return Instant.now().plus(1, ChronoUnit.DAYS);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
