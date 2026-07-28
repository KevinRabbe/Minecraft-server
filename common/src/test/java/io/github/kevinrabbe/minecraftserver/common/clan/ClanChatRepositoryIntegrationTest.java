package io.github.kevinrabbe.minecraftserver.common.clan;

import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
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
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ClanChatRepositoryIntegrationTest {
    private static final String BACKEND_A = "paper-chat-a";
    private static final String BACKEND_B = "paper-chat-b";
    private static final Duration SESSION_LEASE = Duration.ofMinutes(5);

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ClanMembershipRepository clans;
    private ClanChatRepository chat;

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
        sessions = new PlayerSessionRepository(dataSource);
        clans = new ClanMembershipRepository(dataSource);
        chat = new ClanChatRepository(dataSource);
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
                        transfer_tickets,
                        economic_ledger,
                        processed_operations,
                        player_sessions,
                        player_state,
                        player_names,
                        wallets,
                        players,
                        backends
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void publishDerivesClanAndExactReplayIsIdempotent() throws Exception {
        Player outsider = player("ChatOutsider", null);
        assertThrows(
                ClanChatException.class,
                () -> chat.publish(UUID.randomUUID(), outsider.playerId(), outsider.name(), "hello")
        );

        Player leader = player("ChatLeader", BACKEND_A);
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader.playerId(), "Chat Clan", "CHAT");
        UUID messageId = UUID.randomUUID();

        ClanChatMessageSnapshot first = chat.publish(messageId, leader.playerId(), leader.name(), "  hello clan  ");
        ClanChatMessageSnapshot replay = chat.publish(messageId, leader.playerId(), leader.name(), "hello clan");

        assertEquals(first, replay);
        assertEquals(clan.clanId(), first.clanId());
        assertEquals("hello clan", first.body());
        assertEquals(1L, tableCount("clan_chat_messages"));

        assertThrows(
                ClanChatException.class,
                () -> chat.publish(messageId, leader.playerId(), leader.name(), "different")
        );
        assertThrows(SQLException.class, () -> rewriteBody(messageId));
    }

    @Test
    void pollProjectsOnlyCurrentLeasedRecipientsForEachBackend() throws Exception {
        Player leader = player("ChatRouteA", BACKEND_A);
        Player member = player("ChatRouteB", BACKEND_B);
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader.playerId(), "Route Clan", "RTE");
        addMember(clan.clanId(), member.playerId(), ClanRole.MEMBER);

        ClanChatMessageSnapshot message = chat.publish(
                UUID.randomUUID(), leader.playerId(), leader.name(), "cross backend"
        );

        ClanChatDeliveryPage pageA = chat.pollForBackend(BACKEND_A, 0, 100);
        ClanChatDeliveryPage pageB = chat.pollForBackend(BACKEND_B, 0, 100);

        assertEquals(message.sequence(), pageA.scannedThroughSequence());
        assertEquals(message.sequence(), pageB.scannedThroughSequence());
        assertEquals(1, pageA.deliveries().size());
        assertEquals(1, pageB.deliveries().size());
        assertEquals(java.util.List.of(leader.minecraftUuid()), pageA.deliveries().getFirst().recipientMinecraftUuids());
        assertEquals(java.util.List.of(member.minecraftUuid()), pageB.deliveries().getFirst().recipientMinecraftUuids());

        expireSession(member.session().sessionId());
        ClanChatMessageSnapshot afterExpiry = chat.publish(
                UUID.randomUUID(), leader.playerId(), leader.name(), "member expired"
        );
        ClanChatDeliveryPage expiredPage = chat.pollForBackend(BACKEND_B, message.sequence(), 100);
        assertEquals(afterExpiry.sequence(), expiredPage.scannedThroughSequence());
        assertTrue(expiredPage.deliveries().getFirst().recipientMinecraftUuids().isEmpty());
    }

    @Test
    void removedMemberReceivesNothingButBackendCursorStillAdvances() throws Exception {
        Player leader = player("ChatRemoveA", BACKEND_A);
        Player member = player("ChatRemoveB", BACKEND_B);
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader.playerId(), "Remove Clan", "RMV");
        addMember(clan.clanId(), member.playerId(), ClanRole.MEMBER);

        ClanChatMessageSnapshot first = chat.publish(
                UUID.randomUUID(), leader.playerId(), leader.name(), "before removal"
        );
        clans.removeMember(UUID.randomUUID(), clan.clanId(), leader.playerId(), member.playerId());
        ClanChatMessageSnapshot second = chat.publish(
                UUID.randomUUID(), leader.playerId(), leader.name(), "after removal"
        );

        ClanChatDeliveryPage page = chat.pollForBackend(BACKEND_B, first.sequence(), 100);
        assertEquals(second.sequence(), page.scannedThroughSequence());
        assertEquals(1, page.deliveries().size());
        assertTrue(page.deliveries().getFirst().recipientMinecraftUuids().isEmpty());
    }

    @Test
    void retentionCleanupIsBoundedAndRemovesOnlyExpiredRows() throws Exception {
        Player leader = player("ChatCleanup", BACKEND_A);
        ClanSnapshot clan = clans.createClan(UUID.randomUUID(), leader.playerId(), "Cleanup Clan", "CLN");
        insertOldMessage(clan.clanId(), leader.playerId(), leader.name());
        ClanChatMessageSnapshot current = chat.publish(
                UUID.randomUUID(), leader.playerId(), leader.name(), "current"
        );

        assertEquals(1, chat.deleteExpired(Duration.ofHours(1), 1));
        assertEquals(1L, tableCount("clan_chat_messages"));
        assertEquals(current.sequence(), chat.currentSequence());
        assertEquals(0, chat.deleteExpired(Duration.ofHours(1), 1));
    }

    @Test
    void repositoryBoundsMessagePollAndRetentionInputs() throws Exception {
        Player leader = player("ChatBounds", BACKEND_A);
        clans.createClan(UUID.randomUUID(), leader.playerId(), "Bounds Clan", "BND");

        assertThrows(
                ClanChatException.class,
                () -> chat.publish(
                        UUID.randomUUID(), leader.playerId(), leader.name(), "x".repeat(ClanChatRepository.MAX_BODY_CODE_POINTS + 1)
                )
        );
        assertThrows(IllegalArgumentException.class, () -> chat.pollForBackend(BACKEND_A, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> chat.pollForBackend(BACKEND_A, 0, ClanChatRepository.MAX_POLL_LIMIT + 1)
        );
        assertThrows(IllegalArgumentException.class, () -> chat.deleteExpired(Duration.ofSeconds(1), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> chat.deleteExpired(Duration.ofHours(1), ClanChatRepository.MAX_CLEANUP_LIMIT + 1)
        );
    }

    private Player player(String name, String backendId) throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        UUID playerId = identities.ensurePlayer(minecraftUuid, name);
        SessionLease session = backendId == null ? null : sessions.openSession(playerId, backendId, null, SESSION_LEASE);
        return new Player(playerId, minecraftUuid, name, session);
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
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void expireSession(UUID sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_sessions
                     SET lease_expires_at = NOW() - INTERVAL '1 second'
                     WHERE network_session_id = ?
                     """)) {
            statement.setObject(1, sessionId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertOldMessage(UUID clanId, UUID senderPlayerId, String senderName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO clan_chat_messages(
                         message_id,
                         clan_id,
                         sender_player_id,
                         sender_name,
                         body,
                         created_at
                     ) VALUES (?, ?, ?, ?, 'old', NOW() - INTERVAL '2 hours')
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, clanId);
            statement.setObject(3, senderPlayerId);
            statement.setString(4, senderName);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void rewriteBody(UUID messageId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE clan_chat_messages
                     SET body = 'forged'
                     WHERE message_id = ?
                     """)) {
            statement.setObject(1, messageId);
            statement.executeUpdate();
        }
    }

    private long tableCount(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record Player(
            UUID playerId,
            UUID minecraftUuid,
            String name,
            SessionLease session
    ) { }
}
