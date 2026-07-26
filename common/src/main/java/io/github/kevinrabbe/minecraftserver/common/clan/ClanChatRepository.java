package io.github.kevinrabbe.minecraftserver.common.clan;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed bounded transit authority for live cross-backend clan chat. */
public final class ClanChatRepository {
    public static final int MAX_BODY_CODE_POINTS = 256;
    public static final int MAX_POLL_LIMIT = 500;
    public static final int MAX_CLEANUP_LIMIT = 5_000;
    private static final Duration MIN_RETENTION = Duration.ofMinutes(1);
    private static final Duration MAX_RETENTION = Duration.ofDays(30);

    private final DataSource dataSource;

    public ClanChatRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public ClanChatMessageSnapshot publish(
            UUID messageId,
            UUID senderPlayerId,
            String senderName,
            String body
    ) throws SQLException {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(senderPlayerId, "senderPlayerId");
        String normalizedName = normalizeText(senderName, 64, "sender name");
        String normalizedBody = normalizeText(body, MAX_BODY_CODE_POINTS, "clan chat message");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ClanChatMessageSnapshot> existing = loadByMessageId(connection, messageId);
                if (existing.isPresent()) {
                    ClanChatMessageSnapshot replay = requireSameRequest(
                            existing.orElseThrow(), senderPlayerId, normalizedName, normalizedBody
                    );
                    connection.commit();
                    return replay;
                }

                UUID clanId = requireCurrentClan(connection, senderPlayerId);
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO clan_chat_messages(
                            message_id,
                            clan_id,
                            sender_player_id,
                            sender_name,
                            body
                        ) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (message_id) DO NOTHING
                        """)) {
                    statement.setObject(1, messageId);
                    statement.setObject(2, clanId);
                    statement.setObject(3, senderPlayerId);
                    statement.setString(4, normalizedName);
                    statement.setString(5, normalizedBody);
                    inserted = statement.executeUpdate();
                }

                ClanChatMessageSnapshot result = loadByMessageId(connection, messageId)
                        .orElseThrow(() -> new SQLException("Clan chat publish did not produce a message row"));
                if (inserted == 0) {
                    requireSameRequest(result, senderPlayerId, normalizedName, normalizedBody);
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public long currentSequence() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COALESCE(MAX(sequence), 0)
                     FROM clan_chat_messages
                     """);
             ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    public ClanChatDeliveryPage pollForBackend(
            String backendId,
            long afterSequenceExclusive,
            int limit
    ) throws SQLException {
        String normalizedBackend = requireBackendId(backendId);
        if (afterSequenceExclusive < 0) {
            throw new IllegalArgumentException("afterSequenceExclusive must be >= 0");
        }
        requireLimit(limit, MAX_POLL_LIMIT, "clan chat poll limit");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     WITH page AS (
                         SELECT
                             sequence,
                             message_id,
                             clan_id,
                             sender_player_id,
                             sender_name,
                             body,
                             created_at
                         FROM clan_chat_messages
                         WHERE sequence > ?
                         ORDER BY sequence ASC
                         LIMIT ?
                     )
                     SELECT
                         page.sequence,
                         page.message_id,
                         page.clan_id,
                         page.sender_player_id,
                         page.sender_name,
                         page.body,
                         page.created_at,
                         ARRAY(
                             SELECT players.minecraft_uuid
                             FROM clan_members members
                             JOIN players ON players.player_id = members.player_id
                             JOIN player_sessions sessions ON sessions.player_id = members.player_id
                             WHERE members.clan_id = page.clan_id
                               AND sessions.owner_backend_id = ?
                               AND sessions.status = 'ACTIVE'
                               AND sessions.lease_expires_at > NOW()
                             ORDER BY players.minecraft_uuid
                         ) AS recipient_minecraft_uuids
                     FROM page
                     ORDER BY page.sequence ASC
                     """)) {
            statement.setLong(1, afterSequenceExclusive);
            statement.setInt(2, limit);
            statement.setString(3, normalizedBackend);

            ArrayList<ClanChatDelivery> deliveries = new ArrayList<>();
            long scannedThrough = afterSequenceExclusive;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ClanChatMessageSnapshot message = readMessage(rows);
                    scannedThrough = message.sequence();
                    deliveries.add(new ClanChatDelivery(message, readUuidArray(rows, "recipient_minecraft_uuids")));
                }
            }
            return new ClanChatDeliveryPage(scannedThrough, deliveries);
        }
    }

    public int deleteExpired(Duration retention, int limit) throws SQLException {
        Objects.requireNonNull(retention, "retention");
        if (retention.compareTo(MIN_RETENTION) < 0 || retention.compareTo(MAX_RETENTION) > 0) {
            throw new IllegalArgumentException("clan chat retention must be between 1 minute and 30 days");
        }
        requireLimit(limit, MAX_CLEANUP_LIMIT, "clan chat cleanup limit");
        long retentionSeconds = retention.toSeconds();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     WITH expired AS (
                         SELECT sequence
                         FROM clan_chat_messages
                         WHERE created_at < NOW() - make_interval(secs => ?)
                         ORDER BY sequence ASC
                         LIMIT ?
                     )
                     DELETE FROM clan_chat_messages messages
                     USING expired
                     WHERE messages.sequence = expired.sequence
                     """)) {
            statement.setLong(1, retentionSeconds);
            statement.setInt(2, limit);
            return statement.executeUpdate();
        }
    }

    private UUID requireCurrentClan(Connection connection, UUID senderPlayerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT clan_id
                FROM clan_members
                WHERE player_id = ?
                FOR SHARE
                """)) {
            statement.setObject(1, senderPlayerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanChatException("You are not currently in a clan.");
                }
                return row.getObject(1, UUID.class);
            }
        }
    }

    private Optional<ClanChatMessageSnapshot> loadByMessageId(Connection connection, UUID messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    sequence,
                    message_id,
                    clan_id,
                    sender_player_id,
                    sender_name,
                    body,
                    created_at
                FROM clan_chat_messages
                WHERE message_id = ?
                """)) {
            statement.setObject(1, messageId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readMessage(row)) : Optional.empty();
            }
        }
    }

    private static ClanChatMessageSnapshot requireSameRequest(
            ClanChatMessageSnapshot existing,
            UUID senderPlayerId,
            String senderName,
            String body
    ) {
        if (!existing.senderPlayerId().equals(senderPlayerId)
                || !existing.senderName().equals(senderName)
                || !existing.body().equals(body)) {
            throw new ClanChatException("Clan chat message ID was reused for a different request.");
        }
        return existing;
    }

    private static ClanChatMessageSnapshot readMessage(ResultSet row) throws SQLException {
        Timestamp createdAt = row.getTimestamp("created_at");
        if (createdAt == null) {
            throw new SQLException("Clan chat message is missing created_at");
        }
        return new ClanChatMessageSnapshot(
                row.getLong("sequence"),
                row.getObject("message_id", UUID.class),
                row.getObject("clan_id", UUID.class),
                row.getObject("sender_player_id", UUID.class),
                row.getString("sender_name"),
                row.getString("body"),
                createdAt.toInstant()
        );
    }

    private static List<UUID> readUuidArray(ResultSet row, String column) throws SQLException {
        Array sqlArray = row.getArray(column);
        if (sqlArray == null) return List.of();
        try {
            Object raw = sqlArray.getArray();
            if (!(raw instanceof Object[] values)) {
                throw new SQLException("Expected UUID array for " + column);
            }
            ArrayList<UUID> result = new ArrayList<>(values.length);
            for (Object value : values) {
                if (value instanceof UUID uuid) {
                    result.add(uuid);
                } else if (value != null) {
                    result.add(UUID.fromString(value.toString()));
                }
            }
            return List.copyOf(result);
        } finally {
            sqlArray.free();
        }
    }

    private static String normalizeText(String value, int maxCodePoints, String field) {
        if (value == null) throw new ClanChatException(field + " must not be null.");
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new ClanChatException(field + " must not be blank.");
        if (normalized.codePointCount(0, normalized.length()) > maxCodePoints) {
            throw new ClanChatException(field + " must be at most " + maxCodePoints + " characters.");
        }
        return normalized;
    }

    private static String requireBackendId(String backendId) {
        if (backendId == null || backendId.trim().isEmpty()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        return backendId.trim();
    }

    private static void requireLimit(int limit, int maximum, String field) {
        if (limit < 1 || limit > maximum) {
            throw new IllegalArgumentException(field + " must be between 1 and " + maximum);
        }
    }
}
