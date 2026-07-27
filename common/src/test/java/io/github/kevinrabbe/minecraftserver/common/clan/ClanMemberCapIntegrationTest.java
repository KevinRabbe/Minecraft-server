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
import java.util.ArrayList;
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
class ClanMemberCapIntegrationTest {
    private static final int BUNDLED_MEMBER_CAP = 100;

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository clans;
    private ClanPolicyRepository policy;

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
        clans = new ClanMembershipRepository(dataSource);
        policy = new ClanPolicyRepository(dataSource);
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
        assertEquals(BUNDLED_MEMBER_CAP, policy.load().memberCap());
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void concurrentAcceptsCannotExceedConfiguredMemberCap() throws Exception {
        UUID leader = player("CapLeader");
        UUID targetA = player("CapTargetA");
        UUID targetB = player("CapTargetB");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Cap Clan", "CAP");
        fillWithDirectMembers(clan.clanId(), BUNDLED_MEMBER_CAP - 1, "CapFill");
        assertCountsAgree(clan.clanId(), BUNDLED_MEMBER_CAP - 1L);

        ClanInvitationSnapshot inviteA = clans.invite(
                UUID.randomUUID(), clan.clanId(), leader, targetA, futureExpiry()
        );
        ClanInvitationSnapshot inviteB = clans.invite(
                UUID.randomUUID(), clan.clanId(), leader, targetB, futureExpiry()
        );

        int successes = 0;
        int capRejections = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ClanMemberSnapshot> a = executor.submit(
                    () -> clans.acceptInvite(UUID.randomUUID(), inviteA.inviteId(), targetA)
            );
            Future<ClanMemberSnapshot> b = executor.submit(
                    () -> clans.acceptInvite(UUID.randomUUID(), inviteB.inviteId(), targetB)
            );

            for (Future<ClanMemberSnapshot> future : List.of(a, b)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException exception) {
                    assertTrue(exception.getCause() instanceof SQLException);
                    capRejections++;
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1, capRejections);
        assertCountsAgree(clan.clanId(), BUNDLED_MEMBER_CAP);
    }

    @Test
    void rawConcurrentMembershipInsertsCannotBypassDatabaseCap() throws Exception {
        UUID leader = player("RawCapLeader");
        UUID targetA = player("RawCapA");
        UUID targetB = player("RawCapB");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Raw Cap Clan", "RAW");
        fillWithDirectMembers(clan.clanId(), BUNDLED_MEMBER_CAP - 1, "RawFill");
        assertCountsAgree(clan.clanId(), BUNDLED_MEMBER_CAP - 1L);

        int successes = 0;
        int capRejections = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = executor.submit(() -> directMemberInsert(clan.clanId(), targetA));
            Future<Integer> b = executor.submit(() -> directMemberInsert(clan.clanId(), targetB));

            for (Future<Integer> future : List.of(a, b)) {
                try {
                    assertEquals(1, future.get());
                    successes++;
                } catch (ExecutionException exception) {
                    assertTrue(exception.getCause() instanceof SQLException);
                    capRejections++;
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(1, capRejections);
        assertCountsAgree(clan.clanId(), BUNDLED_MEMBER_CAP);
    }

    @Test
    void memberRemovalReleasesExactlyOneReservedSlot() throws Exception {
        UUID leader = player("ReleaseLeader");
        UUID first = player("ReleaseFirst");
        UUID replacement = player("ReleaseNext");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Release Clan", "REL");
        accept(clan.clanId(), leader, first);
        assertCountsAgree(clan.clanId(), 2L);

        clans.removeMember(UUID.randomUUID(), clan.clanId(), leader, first);
        assertCountsAgree(clan.clanId(), 1L);

        accept(clan.clanId(), leader, replacement);
        assertCountsAgree(clan.clanId(), 2L);
    }

    @Test
    void clanMemberIdentityCannotBeMovedAroundCounterAuthority() throws Exception {
        UUID leaderA = player("IdentityLeaderA");
        UUID leaderB = player("IdentityLeaderB");
        UUID member = player("IdentityMember");
        ClanSnapshot clanA = clans.createClan(UUID.randomUUID(), leaderA, "Identity A", "IDA");
        ClanSnapshot clanB = clans.createClan(UUID.randomUUID(), leaderB, "Identity B", "IDB");
        accept(clanA.clanId(), leaderA, member);

        assertThrows(SQLException.class, () -> rewriteMemberClan(clanA.clanId(), clanB.clanId(), member));
        assertCountsAgree(clanA.clanId(), 2L);
        assertCountsAgree(clanB.clanId(), 1L);
    }

    @Test
    void policyMutationRejectsOutOfBoundsValuesWithoutChangingSharedPolicy() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> policy.configureMemberCap(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.configureMemberCap(ClanPolicyRepository.MAX_MEMBER_CAP + 1)
        );
        assertEquals(BUNDLED_MEMBER_CAP, policy.load().memberCap());
    }

    private void fillWithDirectMembers(UUID clanId, int targetTotalMembers, String namePrefix) throws SQLException {
        long current = memberCount(clanId);
        if (targetTotalMembers < current) {
            throw new IllegalArgumentException("targetTotalMembers is below current member count");
        }
        ArrayList<UUID> players = new ArrayList<>();
        for (long index = current; index < targetTotalMembers; index++) {
            players.add(player(namePrefix + index));
        }
        for (UUID playerId : players) {
            assertEquals(1, directMemberInsert(clanId, playerId));
        }
    }

    private void accept(UUID clanId, UUID leader, UUID target) throws SQLException {
        ClanInvitationSnapshot invite = clans.invite(UUID.randomUUID(), clanId, leader, target, futureExpiry());
        clans.acceptInvite(UUID.randomUUID(), invite.inviteId(), target);
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private int directMemberInsert(UUID clanId, UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO clan_members(clan_id, player_id, role)
                     VALUES (?, ?, 'MEMBER')
                     """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            return statement.executeUpdate();
        }
    }

    private void rewriteMemberClan(UUID sourceClanId, UUID targetClanId, UUID playerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_members
                     SET clan_id = ?
                     WHERE clan_id = ? AND player_id = ?
                     """)) {
            statement.setObject(1, targetClanId);
            statement.setObject(2, sourceClanId);
            statement.setObject(3, playerId);
            statement.executeUpdate();
        }
    }

    private void assertCountsAgree(UUID clanId, long expected) throws SQLException {
        assertEquals(expected, memberCount(clanId));
        assertEquals(expected, trackedCount(clanId));
    }

    private long memberCount(UUID clanId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM clan_members
                     WHERE clan_id = ?
                     """)) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long trackedCount(UUID clanId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT member_count
                     FROM clan_member_counts
                     WHERE clan_id = ?
                     """)) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getLong(1);
            }
        }
    }

    private static Instant futureExpiry() {
        return Instant.now().plusSeconds(3600);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
