package io.github.kevinrabbe.minecraftserver.common.pvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable authority for the isolated 1.8.9 Ranked Arena category.
 *
 * <p>The disposable match backend owns no persistent inventory and cannot directly mutate ratings. PostgreSQL owns
 * participant exclusivity, lifecycle, rating state, and immutable completion evidence. Exact operation retries replay
 * the original result; operation IDs cannot be rebound to another request.</p>
 */
public final class RankedArenaRepository {
    private static final String CREATE_OPERATION = "RANKED_ARENA_CREATE";
    private static final String START_OPERATION = "RANKED_ARENA_START";
    private static final String CANCEL_OPERATION = "RANKED_ARENA_CANCEL";
    private static final String COMPLETE_OPERATION = "RANKED_ARENA_COMPLETE";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final RankedArenaRuleset ruleset;

    public RankedArenaRepository(DataSource dataSource, RankedArenaRuleset ruleset) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.ruleset = Objects.requireNonNull(ruleset, "ruleset");
    }

    public Optional<RankedMatchSnapshot> loadMatch(UUID matchId) throws SQLException {
        Objects.requireNonNull(matchId, "matchId");
        try (Connection connection = dataSource.getConnection()) {
            return readMatch(connection, matchId, false);
        }
    }

    public Optional<RankedRatingSnapshot> loadRating(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            return readRating(connection, playerId, false);
        }
    }

    public List<RankedRatingSnapshot> topRatings(int limit) throws SQLException {
        if (limit <= 0 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id, rating, state_version, updated_at
                     FROM ranked_ratings
                     ORDER BY rating DESC, player_id ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<RankedRatingSnapshot> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(ratingSnapshot(rows));
                }
                return List.copyOf(result);
            }
        }
    }

    public RankedMatchSnapshot createMatch(UUID operationId, UUID playerAId, UUID playerBId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerAId, "playerAId");
        Objects.requireNonNull(playerBId, "playerBId");
        if (playerAId.equals(playerBId)) {
            throw new RankedArenaException("ranked match players must be distinct");
        }
        Map<String, Object> request = requestMap(
                "player_a_id", playerAId,
                "player_b_id", playerBId,
                "ruleset_id", ruleset.rulesetId(),
                "ruleset_version", ruleset.rulesetVersion(),
                "rating_policy_version", ruleset.ratingPolicyVersion(),
                "rating_k_factor", ruleset.kFactor()
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    RankedMatchSnapshot replay = matchFrom(requireReplay(
                            processed.orElseThrow(), CREATE_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                requirePlayer(connection, playerAId);
                requirePlayer(connection, playerBId);
                ensureRating(connection, playerAId);
                ensureRating(connection, playerBId);
                lockRatings(connection, playerAId, playerBId);
                requireNoLiveMatch(connection, playerAId, playerBId);

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

                RankedMatchSnapshot result = readMatch(connection, matchId, false).orElseThrow();
                insertProcessed(connection, operationId, CREATE_OPERATION, request, matchMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public RankedMatchSnapshot startMatch(UUID operationId, UUID matchId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(matchId, "matchId");
        Map<String, Object> request = requestMap("match_id", matchId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    RankedMatchSnapshot replay = matchFrom(requireReplay(
                            processed.orElseThrow(), START_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                RankedMatchSnapshot current = requireMatch(connection, matchId, true);
                requireRuleset(current);
                if (current.status() != RankedMatchStatus.CREATED) {
                    throw new RankedArenaException("ranked match is not startable: " + current.status());
                }
                RankedMatchSnapshot result = updateMatchStatus(
                        connection,
                        current,
                        RankedMatchStatus.ACTIVE,
                        null,
                        null
                );
                insertProcessed(connection, operationId, START_OPERATION, request, matchMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public RankedMatchSnapshot cancelMatch(UUID operationId, UUID matchId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(matchId, "matchId");
        Map<String, Object> request = requestMap("match_id", matchId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    RankedMatchSnapshot replay = matchFrom(requireReplay(
                            processed.orElseThrow(), CANCEL_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                RankedMatchSnapshot current = requireMatch(connection, matchId, true);
                requireRuleset(current);
                if (current.status() != RankedMatchStatus.CREATED && current.status() != RankedMatchStatus.ACTIVE) {
                    throw new RankedArenaException("ranked match is not cancellable: " + current.status());
                }
                RankedMatchSnapshot result = updateMatchStatus(
                        connection,
                        current,
                        RankedMatchStatus.CANCELLED,
                        null,
                        null
                );
                insertProcessed(connection, operationId, CANCEL_OPERATION, request, matchMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public RankedMatchResult completeMatch(UUID operationId, UUID matchId, UUID winnerPlayerId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(winnerPlayerId, "winnerPlayerId");
        Map<String, Object> request = requestMap(
                "match_id", matchId,
                "winner_player_id", winnerPlayerId
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    RankedMatchResult replay = matchResultFrom(requireReplay(
                            processed.orElseThrow(), COMPLETE_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                RankedMatchSnapshot current = requireMatch(connection, matchId, true);
                if (current.status() != RankedMatchStatus.ACTIVE) {
                    throw new RankedArenaException("ranked match is not completable: " + current.status());
                }
                if (!winnerPlayerId.equals(current.playerAId()) && !winnerPlayerId.equals(current.playerBId())) {
                    throw new RankedArenaException("winner is not a participant in ranked match: " + winnerPlayerId);
                }

                Map<UUID, RankedRatingSnapshot> lockedRatings = lockRatings(
                        connection,
                        current.playerAId(),
                        current.playerBId()
                );
                RankedRatingSnapshot playerABefore = lockedRatings.get(current.playerAId());
                RankedRatingSnapshot playerBBefore = lockedRatings.get(current.playerBId());
                if (playerABefore == null || playerBBefore == null) {
                    throw new RankedArenaException("ranked rating rows are missing for match participants");
                }

                UUID loserPlayerId = winnerPlayerId.equals(current.playerAId())
                        ? current.playerBId()
                        : current.playerAId();
                int winnerBefore = lockedRatings.get(winnerPlayerId).rating();
                int loserBefore = lockedRatings.get(loserPlayerId).rating();
                int transfer = ratingTransfer(winnerBefore, loserBefore, current.ratingKFactor());
                int winnerAfter = safeRatingAdd(winnerBefore, transfer);
                int loserAfter = loserBefore - transfer;

                RankedRatingSnapshot updatedWinner = updateRating(
                        connection,
                        lockedRatings.get(winnerPlayerId),
                        winnerAfter
                );
                RankedRatingSnapshot updatedLoser = updateRating(
                        connection,
                        lockedRatings.get(loserPlayerId),
                        loserAfter
                );
                RankedRatingSnapshot playerAAfter = winnerPlayerId.equals(current.playerAId())
                        ? updatedWinner : updatedLoser;
                RankedRatingSnapshot playerBAfter = winnerPlayerId.equals(current.playerBId())
                        ? updatedWinner : updatedLoser;

                RankedMatchSnapshot completed = updateMatchStatus(
                        connection,
                        current,
                        RankedMatchStatus.COMPLETED,
                        winnerPlayerId,
                        operationId
                );
                insertResult(
                        connection,
                        completed,
                        operationId,
                        winnerPlayerId,
                        loserPlayerId,
                        playerABefore.rating(),
                        playerAAfter.rating(),
                        playerBBefore.rating(),
                        playerBAfter.rating()
                );

                RankedMatchResult result = new RankedMatchResult(
                        completed,
                        loserPlayerId,
                        playerABefore,
                        playerAAfter,
                        playerBBefore,
                        playerBAfter,
                        completed.ratingPolicyVersion(),
                        completed.ratingKFactor()
                );
                insertProcessed(connection, operationId, COMPLETE_OPERATION, request, matchResultMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private void requireRuleset(RankedMatchSnapshot match) {
        if (!ruleset.rulesetId().equals(match.rulesetId()) || ruleset.rulesetVersion() != match.rulesetVersion()) {
            throw new RankedArenaException(
                    "repository ruleset does not own ranked match " + match.matchId() + ": "
                            + match.rulesetId() + "@" + match.rulesetVersion()
            );
        }
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

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new RankedArenaException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static Map<UUID, RankedRatingSnapshot> lockRatings(
            Connection connection,
            UUID firstPlayerId,
            UUID secondPlayerId
    ) throws SQLException {
        List<UUID> ordered = new ArrayList<>(List.of(firstPlayerId, secondPlayerId));
        ordered.sort(Comparator.comparing(UUID::toString));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id, rating, state_version, updated_at
                FROM ranked_ratings
                WHERE player_id IN (?, ?)
                ORDER BY player_id
                FOR UPDATE
                """)) {
            statement.setObject(1, ordered.get(0));
            statement.setObject(2, ordered.get(1));
            try (ResultSet rows = statement.executeQuery()) {
                Map<UUID, RankedRatingSnapshot> result = new HashMap<>();
                while (rows.next()) {
                    RankedRatingSnapshot snapshot = ratingSnapshot(rows);
                    result.put(snapshot.playerId(), snapshot);
                }
                return result;
            }
        }
    }

    private static void requireNoLiveMatch(Connection connection, UUID first, UUID second) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT match_id, player_id
                FROM ranked_match_participants
                WHERE player_id IN (?, ?)
                  AND released_at IS NULL
                LIMIT 1
                """)) {
            statement.setObject(1, first);
            statement.setObject(2, second);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    throw new RankedArenaException(
                            "player already has a live ranked match: " + row.getObject("player_id", UUID.class)
                    );
                }
            }
        }
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

    private static Optional<RankedMatchSnapshot> readMatch(Connection connection, UUID matchId, boolean forUpdate)
            throws SQLException {
        String sql = """
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
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, matchId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(matchSnapshot(row)) : Optional.empty();
            }
        }
    }

    private static RankedMatchSnapshot requireMatch(Connection connection, UUID matchId, boolean forUpdate)
            throws SQLException {
        return readMatch(connection, matchId, forUpdate)
                .orElseThrow(() -> new RankedArenaException("Unknown ranked match: " + matchId));
    }

    private static Optional<RankedRatingSnapshot> readRating(Connection connection, UUID playerId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT player_id, rating, state_version, updated_at
                FROM ranked_ratings
                WHERE player_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(ratingSnapshot(row)) : Optional.empty();
            }
        }
    }

    private static RankedMatchSnapshot updateMatchStatus(
            Connection connection,
            RankedMatchSnapshot current,
            RankedMatchStatus target,
            UUID winnerPlayerId,
            UUID resultOperationId
    ) throws SQLException {
        long nextVersion = increment(current.stateVersion(), "ranked match", current.matchId());
        String sql = switch (target) {
            case ACTIVE -> """
                    UPDATE ranked_matches
                    SET status = 'ACTIVE', started_at = NOW(), state_version = ?
                    WHERE match_id = ? AND state_version = ? AND status = 'CREATED'
                    RETURNING match_id, player_a_id, player_b_id, status, winner_player_id, result_operation_id,
                              ruleset_id, ruleset_version, rating_policy_version, rating_k_factor, state_version,
                              created_at, started_at, finished_at
                    """;
            case CANCELLED -> """
                    UPDATE ranked_matches
                    SET status = 'CANCELLED', finished_at = NOW(), state_version = ?
                    WHERE match_id = ? AND state_version = ? AND status IN ('CREATED', 'ACTIVE')
                    RETURNING match_id, player_a_id, player_b_id, status, winner_player_id, result_operation_id,
                              ruleset_id, ruleset_version, rating_policy_version, rating_k_factor, state_version,
                              created_at, started_at, finished_at
                    """;
            case COMPLETED -> """
                    UPDATE ranked_matches
                    SET status = 'COMPLETED', winner_player_id = ?, result_operation_id = ?,
                        finished_at = NOW(), state_version = ?
                    WHERE match_id = ? AND state_version = ? AND status = 'ACTIVE'
                    RETURNING match_id, player_a_id, player_b_id, status, winner_player_id, result_operation_id,
                              ruleset_id, ruleset_version, rating_policy_version, rating_k_factor, state_version,
                              created_at, started_at, finished_at
                    """;
            case CREATED -> throw new IllegalArgumentException("cannot transition a ranked match back to CREATED");
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (target == RankedMatchStatus.COMPLETED) {
                statement.setObject(parameter++, winnerPlayerId);
                statement.setObject(parameter++, resultOperationId);
            }
            statement.setLong(parameter++, nextVersion);
            statement.setObject(parameter++, current.matchId());
            statement.setLong(parameter, current.stateVersion());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new RankedArenaException("ranked match changed concurrently: " + current.matchId());
                }
                return matchSnapshot(row);
            }
        }
    }

    private static RankedRatingSnapshot updateRating(
            Connection connection,
            RankedRatingSnapshot current,
            int nextRating
    ) throws SQLException {
        long nextVersion = increment(current.stateVersion(), "ranked rating", current.playerId());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ranked_ratings
                SET rating = ?, state_version = ?, updated_at = NOW()
                WHERE player_id = ? AND state_version = ?
                RETURNING updated_at
                """)) {
            statement.setInt(1, nextRating);
            statement.setLong(2, nextVersion);
            statement.setObject(3, current.playerId());
            statement.setLong(4, current.stateVersion());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new RankedArenaException("ranked rating changed concurrently: " + current.playerId());
                }
                return new RankedRatingSnapshot(
                        current.playerId(),
                        nextRating,
                        nextVersion,
                        row.getTimestamp("updated_at").toInstant()
                );
            }
        }
    }

    private static void insertResult(
            Connection connection,
            RankedMatchSnapshot match,
            UUID operationId,
            UUID winnerPlayerId,
            UUID loserPlayerId,
            int playerABefore,
            int playerAAfter,
            int playerBBefore,
            int playerBAfter
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ranked_match_results(
                    match_id,
                    operation_id,
                    winner_player_id,
                    loser_player_id,
                    player_a_rating_before,
                    player_a_rating_after,
                    player_b_rating_before,
                    player_b_rating_after,
                    ruleset_id,
                    ruleset_version,
                    rating_policy_version,
                    rating_k_factor
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, match.matchId());
            statement.setObject(2, operationId);
            statement.setObject(3, winnerPlayerId);
            statement.setObject(4, loserPlayerId);
            statement.setInt(5, playerABefore);
            statement.setInt(6, playerAAfter);
            statement.setInt(7, playerBBefore);
            statement.setInt(8, playerBAfter);
            statement.setString(9, match.rulesetId());
            statement.setInt(10, match.rulesetVersion());
            statement.setInt(11, match.ratingPolicyVersion());
            statement.setInt(12, match.ratingKFactor());
            statement.executeUpdate();
        }
    }

    private static int ratingTransfer(int winnerRating, int loserRating, int kFactor) {
        double expectedWinner = 1.0d / (1.0d + StrictMath.pow(10.0d, (loserRating - winnerRating) / 400.0d));
        long requested = Math.round(kFactor * (1.0d - expectedWinner));
        long bounded = Math.max(0L, Math.min(requested, loserRating));
        if (bounded > Integer.MAX_VALUE) {
            throw new RankedArenaException("ranked rating transfer overflow");
        }
        return (int) bounded;
    }

    private static int safeRatingAdd(int rating, int delta) {
        try {
            return Math.addExact(rating, delta);
        } catch (ArithmeticException exception) {
            throw new RankedArenaException("ranked rating overflow", exception);
        }
    }

    private static RankedMatchSnapshot matchSnapshot(ResultSet row) throws SQLException {
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
                nullableInstant(row.getTimestamp("started_at")),
                nullableInstant(row.getTimestamp("finished_at"))
        );
    }

    private static RankedRatingSnapshot ratingSnapshot(ResultSet row) throws SQLException {
        return new RankedRatingSnapshot(
                row.getObject("player_id", UUID.class),
                row.getInt("rating"),
                row.getLong("state_version"),
                row.getTimestamp("updated_at").toInstant()
        );
    }

    private static Instant nullableInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Optional<ProcessedOperation> findProcessed(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type, result::text AS result_json
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new ProcessedOperation(
                        row.getString("operation_type"),
                        readJsonMap(row.getString("result_json"))
                ));
            }
        }
    }

    private static Object requireReplay(
            ProcessedOperation processed,
            String operationType,
            Map<String, Object> request,
            UUID operationId
    ) {
        if (!operationType.equals(processed.operationType())) {
            throw new RankedArenaException(
                    "operation_id " + operationId + " already belongs to " + processed.operationType()
            );
        }
        if (!objectMap(processed.data().get("request"), "request").equals(request)) {
            throw new RankedArenaException("operation_id reused with a different ranked request: " + operationId);
        }
        Object result = processed.data().get("result");
        if (result == null) {
            throw new RankedArenaException("processed ranked operation is missing result: " + operationId);
        }
        return result;
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            String operationType,
            Map<String, Object> request,
            Map<String, Object> result
    ) throws SQLException {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("request", request);
        body.put("result", result);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, writeJson(body));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requestMap(Object... fields) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            Object value = fields[index + 1];
            result.put(Objects.toString(fields[index]), value == null ? null : Objects.toString(value));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> matchMap(RankedMatchSnapshot match) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("match_id", match.matchId().toString());
        value.put("player_a_id", match.playerAId().toString());
        value.put("player_b_id", match.playerBId().toString());
        value.put("status", match.status().name());
        value.put("winner_player_id", stringOrNull(match.winnerPlayerId()));
        value.put("result_operation_id", stringOrNull(match.resultOperationId()));
        value.put("ruleset_id", match.rulesetId());
        value.put("ruleset_version", match.rulesetVersion());
        value.put("rating_policy_version", match.ratingPolicyVersion());
        value.put("rating_k_factor", match.ratingKFactor());
        value.put("state_version", match.stateVersion());
        value.put("created_at", match.createdAt().toString());
        value.put("started_at", instantOrNull(match.startedAt()));
        value.put("finished_at", instantOrNull(match.finishedAt()));
        return value;
    }

    private static RankedMatchSnapshot matchFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "match");
        return new RankedMatchSnapshot(
                uuidValue(value, "match_id"),
                uuidValue(value, "player_a_id"),
                uuidValue(value, "player_b_id"),
                RankedMatchStatus.valueOf(stringValue(value, "status")),
                nullableUuid(value.get("winner_player_id")),
                nullableUuid(value.get("result_operation_id")),
                stringValue(value, "ruleset_id"),
                intValue(value, "ruleset_version"),
                intValue(value, "rating_policy_version"),
                intValue(value, "rating_k_factor"),
                longValue(value, "state_version"),
                Instant.parse(stringValue(value, "created_at")),
                nullableInstantValue(value.get("started_at")),
                nullableInstantValue(value.get("finished_at"))
        );
    }

    private static Map<String, Object> ratingMap(RankedRatingSnapshot rating) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("player_id", rating.playerId().toString());
        value.put("rating", rating.rating());
        value.put("state_version", rating.stateVersion());
        value.put("updated_at", rating.updatedAt().toString());
        return value;
    }

    private static RankedRatingSnapshot ratingFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "rating");
        return new RankedRatingSnapshot(
                uuidValue(value, "player_id"),
                intValue(value, "rating"),
                longValue(value, "state_version"),
                Instant.parse(stringValue(value, "updated_at"))
        );
    }

    private static Map<String, Object> matchResultMap(RankedMatchResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("match", matchMap(result.match()));
        value.put("loser_player_id", result.loserPlayerId().toString());
        value.put("player_a_before", ratingMap(result.playerABefore()));
        value.put("player_a_after", ratingMap(result.playerAAfter()));
        value.put("player_b_before", ratingMap(result.playerBBefore()));
        value.put("player_b_after", ratingMap(result.playerBAfter()));
        value.put("rating_policy_version", result.ratingPolicyVersion());
        value.put("rating_k_factor", result.ratingKFactor());
        return value;
    }

    private static RankedMatchResult matchResultFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        return new RankedMatchResult(
                matchFrom(value.get("match")),
                uuidValue(value, "loser_player_id"),
                ratingFrom(value.get("player_a_before")),
                ratingFrom(value.get("player_a_after")),
                ratingFrom(value.get("player_b_before")),
                ratingFrom(value.get("player_b_after")),
                intValue(value, "rating_policy_version"),
                intValue(value, "rating_k_factor")
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new RankedArenaException("Could not parse ranked idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new RankedArenaException("Could not serialize ranked idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new RankedArenaException("ranked field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) throw new RankedArenaException("missing ranked field: " + field);
        return Objects.toString(raw);
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static UUID nullableUuid(Object value) {
        return value == null ? null : UUID.fromString(Objects.toString(value));
    }

    private static int intValue(Map<String, Object> value, String field) {
        return Math.toIntExact(longValue(value, field));
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(Objects.toString(raw));
        } catch (RuntimeException exception) {
            throw new RankedArenaException("invalid ranked numeric field: " + field, exception);
        }
    }

    private static String stringOrNull(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String instantOrNull(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Instant nullableInstantValue(Object value) {
        return value == null ? null : Instant.parse(Objects.toString(value));
    }

    private static long increment(long current, String authority, Object id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new RankedArenaException(authority + " state_version overflow: " + id, exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record ProcessedOperation(String operationType, Map<String, Object> data) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            data = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
        }
    }
}
