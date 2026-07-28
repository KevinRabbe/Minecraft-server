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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanRoleRepositoryIntegrationTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private ClanMembershipRepository memberships;
    private ClanRoleRepository roles;

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
        roles = new ClanRoleRepository(dataSource);
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
    void leaderCanPromoteAndDemoteMemberExactlyOnce() throws Exception {
        UUID leader = player("RoleLeader");
        UUID member = player("RoleMember");
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "Roles", "ROL");
        addMember(clan.clanId(), member, ClanRole.MEMBER);
        UUID promoteOperation = UUID.randomUUID();

        ClanMemberSnapshot promoted = roles.setMemberRole(
                promoteOperation, clan.clanId(), leader, member, ClanRole.OFFICER
        );
        ClanMemberSnapshot retry = roles.setMemberRole(
                promoteOperation, clan.clanId(), leader, member, ClanRole.OFFICER
        );

        assertEquals(promoted, retry);
        assertEquals(ClanRole.OFFICER, memberships.loadMember(member).role());
        assertEquals(ClanRole.MEMBER, roles.setMemberRole(
                UUID.randomUUID(), clan.clanId(), leader, member, ClanRole.MEMBER
        ).role());
    }

    @Test
    void nonLeaderCannotChangeOfficerRolesAndGenericSetterCannotAssignLeader() throws Exception {
        UUID leader = player("RoleLead2");
        UUID officer = player("RoleOfficer");
        UUID member = player("RoleMember2");
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "Roles2", "RO2");
        addMember(clan.clanId(), officer, ClanRole.OFFICER);
        addMember(clan.clanId(), member, ClanRole.MEMBER);

        assertThrows(
                ClanMembershipException.class,
                () -> roles.setMemberRole(UUID.randomUUID(), clan.clanId(), officer, member, ClanRole.OFFICER)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> roles.setMemberRole(UUID.randomUUID(), clan.clanId(), leader, member, ClanRole.LEADER)
        );
        assertEquals(ClanRole.LEADER, memberships.loadMember(leader).role());
        assertEquals(ClanRole.MEMBER, memberships.loadMember(member).role());
    }

    @Test
    void roleOperationIdCannotBeRebound() throws Exception {
        UUID leader = player("RoleLead3");
        UUID member = player("RoleMember3");
        ClanSnapshot clan = memberships.createClan(UUID.randomUUID(), leader, "Roles3", "RO3");
        addMember(clan.clanId(), member, ClanRole.MEMBER);
        UUID operationId = UUID.randomUUID();
        roles.setMemberRole(operationId, clan.clanId(), leader, member, ClanRole.OFFICER);

        assertThrows(
                ClanMembershipException.class,
                () -> roles.setMemberRole(operationId, clan.clanId(), leader, member, ClanRole.MEMBER)
        );
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private void addMember(UUID clanId, UUID playerId, ClanRole role) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO clan_members(clan_id, player_id, role, joined_at)
                     VALUES (?, ?, ?, ?)
                     """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            statement.setString(3, role.name());
            statement.setTimestamp(4, java.sql.Timestamp.from(Instant.now()));
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
