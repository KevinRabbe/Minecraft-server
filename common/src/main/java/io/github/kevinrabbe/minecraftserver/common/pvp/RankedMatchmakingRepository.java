package io.github.kevinrabbe.minecraftserver.common.pvp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit opt-in FIFO matchmaking for Ranked Arena.
 *
 * <p>The queue itself is only durable intent. The second eligible player atomically consumes both queue rows and
 * creates the normal authoritative CREATED ranked match in the same PostgreSQL transaction. Existing competitive
 * reservations are never bypassed, and direct trusted match creation automatically clears stale queue intent through
 * the V67 trigger.</p>
 */
public final class RankedMatchmakingRepository {
    private static final long QUEUE_ADVISORY_LOCK = 0x52414E4B45445155L;

    private final DataSource dataSource;
    private final RankedArenaRuleset ruleset;

    public RankedMatchmakingRepository(DataSource dataSource, RankedArenaRuleset ruleset) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.ruleset = Objects.requireNonNull(ruleset, "ruleset");
    }

    /**
     * Joins (or rechecks) the queue. Empty means the player remains waiting. A present match is either the player's
     * existing live Ranked match or a newly-created FIFO match committed by this call.
     */
    public Optional<RankedMatchSnapshot> join(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockQueue(connection);
                requirePlayer(connection, playerId);

                Optional<RankedMatchSnapshot> existing = findLiveMatch(connection, playerId);
                if (existing.isPresent()) {
                    deleteQueueEntry(connection, playerId);
                    connection.commit();
                    return existing;
                }
                if (hasCompetitiveReservation(connection, playerId)) {
                    deleteQueueEntry(connection, playerId);
                    throw new RankedArenaException("player already has a live competitive execution reservation");
                }

                purgeIneligibleQueueEntries(connection);
                insertQueueEntry(connection, playerId);

                UUID opponentId = findOldestOpponent(connection, playerId);
                if (opponentId == null) {
                    connection.commit();
                    return Optional.empty();
                }

                ensureRating(connection, playerId);
                ensureRating(connection, opponentId);
                lockRatings(connection, playerId, opponentId);

                Optional<RankedMatchSnapshot> currentLive = findLiveMatch(connection, playerId);
                if (currentLive.isPresent()) {
                    deleteQueueEntry(connection, playerId);
                    connection.commit();
                    return currentLive;
                }
                if (findLiveMatch(connection, opponentId).isPresent()) {
                    deleteQueueEntry(connection, opponentId);
                    connection.commit();
                    return Optional.empty();
                }
                if (hasCompetitiveReservation(connection, playerId)) {
                    deleteQueueEntry(connection, playerId);
                    throw new RankedArenaException("player became reserved for another competitive execution");
                }
                if (hasCompetitiveReservation(connection, opponentId)) {
                    deleteQueueEntry(connection, opponentId);
                    connection.commit();
                    return Optional.empty();
                }

                RankedMatchSnapshot match = createMatch(connection, opponentId, playerId);
                deleteQueueEntry(connection, opponentId);
                deleteQueueEntry(connection, playerId);
                connection.commit();
                return Optional.of(match);
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Cancels only still-waiting queue intent. Once a match exists, normal Ranked lifecycle authority owns it. */
    public boolean leave(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockQueue(connection);
                boolean removed = deleteQueueEntry(connection, playerId);
                connection.commit();
                return removed;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public Optional<Instant> queuedAt(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT joined_at
                     FROM ranked_matchmaking_queue
                     WHERE player_id = ?
                     """)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(row.getTimestamp("joined_at").toInstant()) : Optional.empty();
            }
        }
    }

    public Optional<RankedMatchSnapshot> liveMatch(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            return findLiveMatch(connection, playerId);
        }
    }

    private RankedMatchSnapshot createMatch(Connection connection, UUID playerAId, UUID playerBId) throws SQLException {
        UUID matchId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ranked_matches(
                    match_id,
                    player_a_id,
                    player_b_id,
                    status,
                    ruleset_id,
                    ruleset_version,
                    rating_policy_version,
                    rating_k_factor,
                    state_version
                ) VALUES (?, ?, ?, 'CREATED', ?, ?, ?, ?, 0)
                """)) {
            statement.setObject(1, matchId);
            statement.setObject(2, playerAId);
            statement.setObject(3, playerBId);
            statement.setString(4, ruleset.rulesetId());
            statement.setInt(5, ruleset.rulesetVersion());
            statement.setInt(6, ruleset.ratingPolicyVersion());
            statement.setInt(7, ruleset.kFactor());
            statement.executeUpdate();
        }
        insertParticipant(connection, matchId, playerAId);
        insertParticipant(connection, matchId, playerBId);
        return loadMatch(connection, matchId).orElseThrow(
                () -> new SQLException("Ranked matchmaking committed a match row that could not be reloaded: " + matchId)
        );
    }

    private void ensureRating(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ranked_ratings(player_id, rating, state_version)
                VALUES (?, ?, 0)
                ON CONFLICT (player_id) DO NOTHING
                """)) {
            statement.setObject(1, playerId);
            statement.setInt(2, ruleset.initialRating());
            statement.executeUpdate();
        }
    }

    private static void lockRatings(Connection connection, UUID firstPlayerId, UUID secondPlayerId) throws SQLException {
        List<UUID> ordered = new ArrayList<>(List.of(firstPlayerId, secondPlayerId));
        ordered.sort(Comparator.comparing(UUID::toString));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id
                FROM ranked_ratings
                WHERE player_id IN (?, ?)
                ORDER BY player_id
                FOR UPDATE
                """)) {
            statement.setObject(1, ordered.get(0));
            statement.setObject(2, ordered.get(1));
            try (ResultSet rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) count++;
                if (count != 2) {
                    throw new RankedArenaException("ranked rating rows are missing during matchmaking");
                }
            }
        }
    }

    private static Optional<RankedMatchSnapshot> findLiveMatch(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT m.match_id,
                       m.player_a_id,
                       m.player_b_id,
                       m.status,
                       m.winner_player_id,
                       m.result_operation_id,
                       m.ruleset_id,
                       m.ruleset_version,
                       m.rating_policy_version,
                       m.rating_k_factor,
                       m.state_version,
                       m.created_at,
                       m.started_at,
                       m.finished_at
                FROM ranked_match_participants participant
                JOIN ranked_matches m ON m.match_id = participant.match_id
                WHERE participant.player_id = ?
                  AND participant.released_at IS NULL
                  AND m.status IN ('CREATED', 'ACTIVE')
                ORDER BY m.created_at ASC, m.match_id ASC
                LIMIT 2
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                RankedMatchSnapshot result = readMatch(rows);
                if (rows.next()) {
                    throw new RankedArenaException("player has multiple live ranked matches: " + playerId);
                }
                return Optional.of(result);
            }
        }
    }

    private static Optional<RankedMatchSnapshot> loadMatch(Connection connection, UUID matchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT match_id,
                       player_a_id,
                       player_b_id,
                       status,
                       winner_player_id,
                       result_operation_id,
                       ruleset_id,
                       ruleset_version,
                       rating_policy_version,
                       rating_k_factor,
                       state_version,
                       created_at,
                       started_at,
                       finished_at
                FROM ranked_matches
                WHERE match_id = ?
                """)) {
            statement.setObject(1, matchId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readMatch(row)) : Optional.empty();
            }
        }
    }

    private static RankedMatchSnapshot readMatch(ResultSet row) throws SQLException {
        return new RankedMatchSnapshot(
                row.getObject("match_id", UUID.class),
                row.getObject("player_a_id", UUID.class),
                row.getObject("player_b_id", UUID.class),
                RankedMatchStatus.valueOf(row.getString("status")),
                row.getObject("winner_player_id", UUID.class),
                row.getObject("result_operation_id", UUID.class),
                row.getString("ruleset_id"),
                row.getInt("ruleset_version"),
                row.getInt("rating_policy_version"),
                row.getInt("rating_k_factor"),
                row.getLong("state_version"),
                row.getTimestamp("created_at").toInstant(),
                toInstant(row, "started_at"),
                toInstant(row, "finished_at")
        );
    }

    private static Instant toInstant(ResultSet row, String column) throws SQLException {
        var timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void insertParticipant(Connection connection, UUID matchId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ranked_match_participants(match_id, player_id)
                VALUES (?, ?)
                """)) {
            statement.setObject(1, matchId);
            statement.setObject(2, playerId);
            statement.executeUpdate();
        }
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new RankedArenaException("Unknown player_id: " + playerId);
            }
        }
    }

    private static boolean hasCompetitiveReservation(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM competitive_player_execution_reservations
                WHERE player_id = ?
                LIMIT 1
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private static void purgeIneligibleQueueEntries(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM ranked_matchmaking_queue queue_entry
                WHERE EXISTS (
                    SELECT 1
                    FROM competitive_player_execution_reservations reservation
                    WHERE reservation.player_id = queue_entry.player_id
                )
                OR EXISTS (
                    SELECT 1
                    FROM ranked_match_participants participant
                    WHERE participant.player_id = queue_entry.player_id
                      AND participant.released_at IS NULL
                )
                """)) {
            statement.executeUpdate();
        }
    }

    private static void insertQueueEntry(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ranked_matchmaking_queue(player_id)
                VALUES (?)
                ON CONFLICT (player_id) DO NOTHING
                """)) {
            statement.setObject(1, playerId);
            statement.executeUpdate();
        }
    }

    private static UUID findOldestOpponent(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id
                FROM ranked_matchmaking_queue
                WHERE player_id <> ?
                ORDER BY joined_at ASC, player_id ASC
                LIMIT 1
                FOR UPDATE
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject("player_id", UUID.class) : null;
            }
        }
    }

    private static boolean deleteQueueEntry(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM ranked_matchmaking_queue
                WHERE player_id = ?
                """)) {
            statement.setObject(1, playerId);
            return statement.executeUpdate() == 1;
        }
    }

    private static void lockQueue(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, QUEUE_ADVISORY_LOCK);
            statement.executeQuery().close();
        }
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
