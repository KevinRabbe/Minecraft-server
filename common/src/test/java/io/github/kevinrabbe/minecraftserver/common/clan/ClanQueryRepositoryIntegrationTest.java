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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanQueryRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private ClanQueryRepository queries;

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
        queries = new ClanQueryRepository(dataSource);
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
    void rosterUsesRoleOrderStableIdentityAndCurrentNameProjection() throws Exception {
        UUID leader = player("RosterLeader");
        UUID officer = player("RosterOfficer");
        UUID memberMinecraft = UUID.randomUUID();
        UUID member = identities.ensurePlayer(memberMinecraft, "RosterOld");
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "Roster", "RST");
        addMember(clan.clanId(), member, ClanRole.MEMBER);
        addMember(clan.clanId(), officer, ClanRole.OFFICER);
        identities.ensurePlayer(memberMinecraft, "RosterNew");

        List<ClanMemberView> result = queries.listMembers(clan.clanId(), 10);

        assertEquals(3, result.size());
        assertEquals(ClanRole.LEADER, result.get(0).role());
        assertEquals(leader, result.get(0).playerId());
        assertEquals(ClanRole.OFFICER, result.get(1).role());
        assertEquals(officer, result.get(1).playerId());
        assertEquals(ClanRole.MEMBER, result.get(2).role());
        assertEquals(member, result.get(2).playerId());
        assertEquals("RosterNew", result.get(2).playerName());
        assertEquals(result, queries.listMembers(clan.clanId(), 10));
    }

    @Test
    void currentMemberNameResolutionIsClanScopedCaseInsensitiveAndIgnoresHistoricalNames() throws Exception {
        UUID leader = player("ResolveLeader");
        UUID memberMinecraft = UUID.randomUUID();
        UUID member = identities.ensurePlayer(memberMinecraft, "ResolveOld");
        UUID outsider = player("ResolveOutside");
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "Resolvers", "RSLV");
        addMember(clan.clanId(), member, ClanRole.MEMBER);
        identities.ensurePlayer(memberMinecraft, "ResolveNew");

        ClanMemberView resolved = queries.findMemberByCurrentName(clan.clanId(), "resolvenew").orElseThrow();
        assertEquals(member, resolved.playerId());
        assertEquals("ResolveNew", resolved.playerName());
        assertTrue(queries.findMemberByCurrentName(clan.clanId(), "ResolveOld").isEmpty());
        assertTrue(queries.findMemberByCurrentName(clan.clanId(), "ResolveOutside").isEmpty());

        ClanSnapshot outsiderClan = memberships.createClan(UUID.randomUUID(), outsider, "Outsiders", "OUTS");
        assertTrue(queries.findMemberByCurrentName(outsiderClan.clanId(), "ResolveNew").isEmpty());
    }

    @Test
    void pendingInvitesExcludeCancelledAndExpiredRowsAndAreBounded() throws Exception {
        UUID leader = player("InviteLead");
        UUID targetA = player("InviteA");
        UUID targetB = player("InviteB");
        UUID targetCMinecraft = UUID.randomUUID();
        UUID targetC = identities.ensurePlayer(targetCMinecraft, "InviteCOld");
        UUID expiredTarget = player("InviteExpired");
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "Inviters", "INV");

        ClanInvitationSnapshot pendingA = memberships.invite(
                UUID.randomUUID(), clan.clanId(), leader, targetA, futureExpiry()
        );
        ClanInvitationSnapshot cancelled = memberships.invite(
                UUID.randomUUID(), clan.clanId(), leader, targetB, futureExpiry()
        );
        memberships.cancelInvite(UUID.randomUUID(), cancelled.inviteId(), leader);
        ClanInvitationSnapshot pendingC = memberships.invite(
                UUID.randomUUID(), clan.clanId(), leader, targetC, futureExpiry()
        );
        identities.ensurePlayer(targetCMinecraft, "InviteCNew");
        insertExpiredPendingInvite(clan.clanId(), leader, expiredTarget);

        List<ClanInvitationView> result = queries.listPendingInvitations(clan.clanId(), 10);

        assertEquals(2, result.size());
        assertEquals(List.of(pendingA.inviteId(), pendingC.inviteId()), result.stream().map(ClanInvitationView::inviteId).toList());
        assertEquals("InviteCNew", result.get(1).invitedPlayerName());
        assertEquals("InviteLead", result.get(0).invitedByPlayerName());
        assertEquals(1, queries.listPendingInvitations(clan.clanId(), 1).size());
    }

    @Test
    void rejectsUnboundedClanQueries() {
        UUID clanId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> queries.listMembers(clanId, 0));
        assertThrows(IllegalArgumentException.class, () -> queries.listMembers(clanId, 101));
        assertThrows(IllegalArgumentException.class, () -> queries.listPendingInvitations(clanId, 0));
        assertThrows(IllegalArgumentException.class, () -> queries.listPendingInvitations(clanId, 101));
        assertThrows(IllegalArgumentException.class, () -> queries.findMemberByCurrentName(clanId, ""));
        assertThrows(IllegalArgumentException.class, () -> queries.findMemberByCurrentName(clanId, "abcdefghijklmnopq"));
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void addMember(UUID clanId, UUID playerId, ClanRole role) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO clan_members(clan_id, player_id, role)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            statement.setString(3, role.name());
            statement.executeUpdate();
        }
    }

    private void insertExpiredPendingInvite(UUID clanId, UUID inviterId, UUID invitedId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO clan_invitations(
                         invite_id,
                         clan_id,
                         invited_player_id,
                         invited_by_player_id,
                         status,
                         created_at,
                         expires_at
                     ) VALUES (?, ?, ?, ?, 'PENDING', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour')
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, clanId);
            statement.setObject(3, invitedId);
            statement.setObject(4, inviterId);
            statement.executeUpdate();
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
