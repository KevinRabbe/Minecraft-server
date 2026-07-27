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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
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
        policy.configureMemberCap(100);
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void concurrentAcceptsCannotExceedConfiguredMemberCap() throws Exception {
        policy.configureMemberCap(2);
        UUID leader = player("CapLeader");
        UUID targetA = player("CapTargetA");
        UUID targetB = player("CapTargetB");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Cap Clan", "CAP");
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

            for (Future<ClanMemberSnapshot> future : java.util.List.of(a, b)) {
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
        assertEquals(2L, memberCount(clan.clanId()));
    }

    @Test
    void loweringCapDoesNotEvictExistingMembersButBlocksFurtherJoins() throws Exception {
        policy.configureMemberCap(3);
        UUID leader = player("LowerLeader");
        UUID first = player("LowerFirst");
        UUID second = player("LowerSecond");
        UUID blocked = player("LowerBlocked");
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader, "Lower Clan", "LOW");

        accept(clan.clanId(), leader, first);
        accept(clan.clanId(), leader, second);
        assertEquals(3L, memberCount(clan.clanId()));

        ClanPolicySnapshot lowered = policy.configureMemberCap(2);
        assertEquals(2, lowered.memberCap());
        ClanInvitationSnapshot blockedInvite = clans.invite(
                UUID.randomUUID(), clan.clanId(), leader, blocked, futureExpiry()
        );

        assertThrows(
                SQLException.class,
                () -> clans.acceptInvite(UUID.randomUUID(), blockedInvite.inviteId(), blocked)
        );
        assertEquals(3L, memberCount(clan.clanId()));
    }

    @Test
    void policyMutationIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> policy.configureMemberCap(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.configureMemberCap(ClanPolicyRepository.MAX_MEMBER_CAP + 1)
        );
    }

    private void accept(UUID clanId, UUID leader, UUID target) throws SQLException {
        ClanInvitationSnapshot invite = clans.invite(UUID.randomUUID(), clanId, leader, target, futureExpiry());
        clans.acceptInvite(UUID.randomUUID(), invite.inviteId(), target);
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private long memberCount(UUID clanId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement("""
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
