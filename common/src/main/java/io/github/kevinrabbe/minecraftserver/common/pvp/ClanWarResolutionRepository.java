package io.github.kevinrabbe.minecraftserver.common.pvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
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

/** Exactly-once terminal settlement/recovery authority for 1.8.9 Clan Wars. */
public final class ClanWarResolutionRepository {
    private static final String COMPLETE_OPERATION = "CLAN_WAR_COMPLETE";
    private static final String CANCEL_OPERATION = "CLAN_WAR_CANCEL";
    private static final String FAIL_OPERATION = "CLAN_WAR_FAIL";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public ClanWarResolutionRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Optional<ClanWarRatingSnapshot> loadRating(UUID clanId) throws SQLException {
        Objects.requireNonNull(clanId, "clanId");
        try (Connection connection = dataSource.getConnection()) {
            return readRating(connection, clanId);
        }
    }

    public List<ClanWarRatingSnapshot> topRatings(int limit) throws SQLException {
        if (limit <= 0 || limit > 1_000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT clan_id, rating, state_version, updated_at
                     FROM clan_war_ratings
                     ORDER BY rating DESC, clan_id ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<ClanWarRatingSnapshot> result = new ArrayList<>();
                while (rows.next()) result.add(ratingSnapshot(rows));
                return List.copyOf(result);
            }
        }
    }

    public ClanWarCompletionResult complete(UUID operationId, UUID warId, UUID winningClanId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(warId, "warId");
        Objects.requireNonNull(winningClanId, "winningClanId");
        Map<String, Object> request = requestMap("war_id", warId, "winning_clan_id", winningClanId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ClanWarCompletionResult replay = completionFrom(requireReplay(
                            processed.orElseThrow(), COMPLETE_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                ClanWarSnapshot current = ClanWarLifecycleRepository.requireWar(connection, warId, true);
                if (current.status() != ClanWarStatus.ACTIVE) {
                    throw new ClanWarException("clan war is not completable: " + current.status());
                }
                if (!winningClanId.equals(current.challengerClanId())
                        && !winningClanId.equals(current.defenderClanId())) {
                    throw new ClanWarException("winning clan is not a participant in war");
                }
                UUID losingClanId = winningClanId.equals(current.challengerClanId())
                        ? current.defenderClanId()
                        : current.challengerClanId();

                Map<UUID, ClanWarRatingSnapshot> ratings = lockRatings(
                        connection, current.challengerClanId(), current.defenderClanId()
                );
                ClanWarRatingSnapshot challengerBefore = requireRating(ratings, current.challengerClanId());
                ClanWarRatingSnapshot defenderBefore = requireRating(ratings, current.defenderClanId());
                ClanWarRatingSnapshot winnerBefore = requireRating(ratings, winningClanId);
                ClanWarRatingSnapshot loserBefore = requireRating(ratings, losingClanId);
                int transfer = ratingTransfer(winnerBefore.rating(), loserBefore.rating(), current.ratingKFactor());
                ClanWarRatingSnapshot winnerAfter = updateRating(
                        connection, winnerBefore, safeRatingAdd(winnerBefore.rating(), transfer)
                );
                ClanWarRatingSnapshot loserAfter = updateRating(
                        connection, loserBefore, loserBefore.rating() - transfer
                );
                ClanWarRatingSnapshot challengerAfter = winningClanId.equals(current.challengerClanId())
                        ? winnerAfter : loserAfter;
                ClanWarRatingSnapshot defenderAfter = winningClanId.equals(current.defenderClanId())
                        ? winnerAfter : loserAfter;

                List<UUID> deliveries = releaseCustody(
                        connection, current, operationId, "clan.war_complete_return", null
                );
                ClanWarSnapshot completed = terminalTransition(
                        connection, current, ClanWarStatus.COMPLETED, operationId, winningClanId
                );
                insertResult(
                        connection,
                        completed,
                        operationId,
                        winningClanId,
                        losingClanId,
                        challengerBefore.rating(),
                        challengerAfter.rating(),
                        defenderBefore.rating(),
                        defenderAfter.rating()
                );

                ClanWarCompletionResult result = new ClanWarCompletionResult(
                        completed,
                        losingClanId,
                        challengerBefore,
                        challengerAfter,
                        defenderBefore,
                        defenderAfter,
                        deliveries
                );
                insertProcessed(connection, operationId, COMPLETE_OPERATION, request, completionMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanWarTerminalResult cancel(UUID operationId, UUID warId, UUID actingPlayerId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(warId, "warId");
        Objects.requireNonNull(actingPlayerId, "actingPlayerId");
        Map<String, Object> request = requestMap("war_id", warId, "acting_player_id", actingPlayerId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ClanWarTerminalResult replay = terminalFrom(requireReplay(
                            processed.orElseThrow(), CANCEL_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                ClanWarSnapshot current = ClanWarLifecycleRepository.requireWar(connection, warId, true);
                if (current.status() != ClanWarStatus.CHALLENGED
                        && current.status() != ClanWarStatus.ACCEPTED
                        && current.status() != ClanWarStatus.ROSTER_LOCKED) {
                    throw new ClanWarException("clan war is not cancellable: " + current.status());
                }
                requireParticipantOfficer(connection, current, actingPlayerId);
                List<UUID> deliveries = releaseCustody(
                        connection, current, operationId, "clan.war_cancel_return", actingPlayerId
                );
                ClanWarSnapshot cancelled = terminalTransition(
                        connection, current, ClanWarStatus.CANCELLED, operationId, null
                );
                ClanWarTerminalResult result = new ClanWarTerminalResult(cancelled, deliveries);
                insertProcessed(connection, operationId, CANCEL_OPERATION, request, terminalMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanWarTerminalResult fail(UUID operationId, UUID warId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(warId, "warId");
        Map<String, Object> request = requestMap("war_id", warId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ClanWarTerminalResult replay = terminalFrom(requireReplay(
                            processed.orElseThrow(), FAIL_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                ClanWarSnapshot current = ClanWarLifecycleRepository.requireWar(connection, warId, true);
                if (current.status() != ClanWarStatus.ROSTER_LOCKED && current.status() != ClanWarStatus.ACTIVE) {
                    throw new ClanWarException("clan war is not fail-resolvable: " + current.status());
                }
                List<UUID> deliveries = releaseCustody(
                        connection, current, operationId, "clan.war_failure_return", null
                );
                ClanWarSnapshot failed = terminalTransition(
                        connection, current, ClanWarStatus.FAILED, operationId, null
                );
                ClanWarTerminalResult result = new ClanWarTerminalResult(failed, deliveries);
                insertProcessed(connection, operationId, FAIL_OPERATION, request, terminalMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static void requireParticipantOfficer(Connection connection, ClanWarSnapshot war, UUID playerId)
            throws SQLException {
        try {
            ClanWarLifecycleRepository.requireOfficerOrLeader(connection, war.challengerClanId(), playerId);
            return;
        } catch (ClanWarException ignored) {
            // Try the other participant clan before rejecting.
        }
        ClanWarLifecycleRepository.requireOfficerOrLeader(connection, war.defenderClanId(), playerId);
    }

    private static ClanWarSnapshot terminalTransition(
            Connection connection,
            ClanWarSnapshot current,
            ClanWarStatus target,
            UUID operationId,
            UUID winningClanId
    ) throws SQLException {
        long nextVersion = ClanWarLifecycleRepository.increment(current.stateVersion(), "clan war", current.warId());
        String sql = switch (target) {
            case COMPLETED -> """
                    UPDATE clan_wars
                    SET status = 'COMPLETED',
                        winning_clan_id = ?,
                        settlement_operation_id = ?,
                        resolution_operation_id = ?,
                        finished_at = NOW(),
                        state_version = ?
                    WHERE war_id = ? AND state_version = ? AND status = 'ACTIVE'
                    RETURNING *
                    """;
            case CANCELLED -> """
                    UPDATE clan_wars
                    SET status = 'CANCELLED',
                        resolution_operation_id = ?,
                        finished_at = NOW(),
                        state_version = ?
                    WHERE war_id = ? AND state_version = ? AND status IN ('CHALLENGED', 'ACCEPTED', 'ROSTER_LOCKED')
                    RETURNING *
                    """;
            case FAILED -> """
                    UPDATE clan_wars
                    SET status = 'FAILED',
                        resolution_operation_id = ?,
                        finished_at = NOW(),
                        state_version = ?
                    WHERE war_id = ? AND state_version = ? AND status IN ('ROSTER_LOCKED', 'ACTIVE')
                    RETURNING *
                    """;
            default -> throw new IllegalArgumentException("unsupported terminal target: " + target);
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (target == ClanWarStatus.COMPLETED) {
                statement.setObject(parameter++, winningClanId);
                statement.setObject(parameter++, operationId);
                statement.setObject(parameter++, operationId);
            } else {
                statement.setObject(parameter++, operationId);
            }
            statement.setLong(parameter++, nextVersion);
            statement.setObject(parameter++, current.warId());
            statement.setLong(parameter, current.stateVersion());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ClanWarException("clan war changed concurrently: " + current.warId());
                return ClanWarLifecycleRepository.warSnapshot(row);
            }
        }
    }

    private static List<UUID> releaseCustody(
            Connection connection,
            ClanWarSnapshot war,
            UUID parentOperationId,
            String reason,
            UUID actorPlayerId
    ) throws SQLException {
        List<LockedWarItem> items = lockActiveWarItems(connection, war.warId());
        List<UUID> deliveryIds = new ArrayList<>(items.size());
        for (LockedWarItem item : items) {
            if (!"WAR_CUSTODY".equals(item.locationKind())
                    || !war.warId().equals(item.locationId())
                    || item.stateVersion() != item.entryItemVersion()) {
                throw new ClanWarException("war item no longer matches authoritative custody: " + item.itemInstanceId());
            }
            UUID deliveryId = deterministicUuid(parentOperationId, item.itemInstanceId(), "return-delivery");
            UUID issueOperationId = deterministicUuid(parentOperationId, item.itemInstanceId(), "return-issue");
            insertPendingUniqueDelivery(
                    connection, deliveryId, item.playerId(), item.itemInstanceId(), issueOperationId, reason
            );
            long nextVersion = ClanWarLifecycleRepository.increment(
                    item.stateVersion(), "war item", item.itemInstanceId()
            );
            moveItemToPendingDelivery(
                    connection,
                    item.itemInstanceId(),
                    war.warId(),
                    item.stateVersion(),
                    nextVersion,
                    deliveryId
            );
            insertReturnProvenance(
                    connection,
                    item.itemInstanceId(),
                    nextVersion,
                    issueOperationId,
                    war.warId(),
                    deliveryId,
                    reason,
                    actorPlayerId
            );
            markWarItemReleased(connection, war.warId(), item.itemInstanceId());
            deliveryIds.add(deliveryId);
        }
        return List.copyOf(deliveryIds);
    }

    private static List<LockedWarItem> lockActiveWarItems(Connection connection, UUID warId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT w.player_id,
                       w.item_instance_id,
                       w.entry_item_version,
                       i.location_kind,
                       i.location_id,
                       i.state_version
                FROM clan_war_items w
                JOIN item_instances i ON i.item_instance_id = w.item_instance_id
                WHERE w.war_id = ? AND w.released_at IS NULL
                ORDER BY w.item_instance_id
                FOR UPDATE OF w, i
                """)) {
            statement.setObject(1, warId);
            try (ResultSet rows = statement.executeQuery()) {
                List<LockedWarItem> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new LockedWarItem(
                            rows.getObject("player_id", UUID.class),
                            rows.getObject("item_instance_id", UUID.class),
                            rows.getLong("entry_item_version"),
                            rows.getString("location_kind"),
                            rows.getObject("location_id", UUID.class),
                            rows.getLong("state_version")
                    ));
                }
                return result;
            }
        }
    }

    private static void insertPendingUniqueDelivery(
            Connection connection,
            UUID deliveryId,
            UUID playerId,
            UUID itemInstanceId,
            UUID issueOperationId,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_unique_deliveries(
                    delivery_id,
                    recipient_player_id,
                    item_instance_id,
                    status,
                    issue_operation_id,
                    issue_reason
                ) VALUES (?, ?, ?, 'PENDING', ?, ?)
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, playerId);
            statement.setObject(3, itemInstanceId);
            statement.setObject(4, issueOperationId);
            statement.setString(5, reason);
            statement.executeUpdate();
        }
    }

    private static void moveItemToPendingDelivery(
            Connection connection,
            UUID itemInstanceId,
            UUID warId,
            long expectedVersion,
            long nextVersion,
            UUID deliveryId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = 'PENDING_DELIVERY',
                    location_id = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ?
                  AND location_kind = 'WAR_CUSTODY'
                  AND location_id = ?
                  AND state_version = ?
                """)) {
            statement.setObject(1, deliveryId);
            statement.setLong(2, nextVersion);
            statement.setObject(3, itemInstanceId);
            statement.setObject(4, warId);
            statement.setLong(5, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new ClanWarException("war item changed concurrently during return: " + itemInstanceId);
            }
        }
    }

    private static void insertReturnProvenance(
            Connection connection,
            UUID itemInstanceId,
            long sequenceNo,
            UUID operationId,
            UUID warId,
            UUID deliveryId,
            String reason,
            UUID actorPlayerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO item_provenance(
                    item_instance_id,
                    sequence_no,
                    operation_id,
                    event_type,
                    from_location_kind,
                    from_location_id,
                    to_location_kind,
                    to_location_id,
                    reason,
                    actor_player_id
                ) VALUES (?, ?, ?, 'MOVED', 'WAR_CUSTODY', ?, 'PENDING_DELIVERY', ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, sequenceNo);
            statement.setObject(3, operationId);
            statement.setObject(4, warId);
            statement.setObject(5, deliveryId);
            statement.setString(6, reason);
            if (actorPlayerId == null) statement.setNull(7, java.sql.Types.OTHER);
            else statement.setObject(7, actorPlayerId);
            statement.executeUpdate();
        }
    }

    private static void markWarItemReleased(Connection connection, UUID warId, UUID itemInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE clan_war_items
                SET released_at = NOW()
                WHERE war_id = ? AND item_instance_id = ? AND released_at IS NULL
                """)) {
            statement.setObject(1, warId);
            statement.setObject(2, itemInstanceId);
            if (statement.executeUpdate() != 1) {
                throw new ClanWarException("war item release evidence changed concurrently: " + itemInstanceId);
            }
        }
    }

    private static Map<UUID, ClanWarRatingSnapshot> lockRatings(
            Connection connection,
            UUID firstClanId,
            UUID secondClanId
    ) throws SQLException {
        List<UUID> ordered = new ArrayList<>(List.of(firstClanId, secondClanId));
        ordered.sort(Comparator.comparing(UUID::toString));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT clan_id, rating, state_version, updated_at
                FROM clan_war_ratings
                WHERE clan_id IN (?, ?)
                ORDER BY clan_id
                FOR UPDATE
                """)) {
            statement.setObject(1, ordered.get(0));
            statement.setObject(2, ordered.get(1));
            try (ResultSet rows = statement.executeQuery()) {
                Map<UUID, ClanWarRatingSnapshot> result = new HashMap<>();
                while (rows.next()) {
                    ClanWarRatingSnapshot snapshot = ratingSnapshot(rows);
                    result.put(snapshot.clanId(), snapshot);
                }
                return result;
            }
        }
    }

    private static ClanWarRatingSnapshot requireRating(Map<UUID, ClanWarRatingSnapshot> ratings, UUID clanId) {
        ClanWarRatingSnapshot rating = ratings.get(clanId);
        if (rating == null) throw new ClanWarException("missing Clan-War rating for clan: " + clanId);
        return rating;
    }

    private static Optional<ClanWarRatingSnapshot> readRating(Connection connection, UUID clanId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT clan_id, rating, state_version, updated_at
                FROM clan_war_ratings WHERE clan_id = ?
                """)) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(ratingSnapshot(row)) : Optional.empty();
            }
        }
    }

    private static ClanWarRatingSnapshot updateRating(
            Connection connection,
            ClanWarRatingSnapshot current,
            int nextRating
    ) throws SQLException {
        long nextVersion = ClanWarLifecycleRepository.increment(current.stateVersion(), "clan-war rating", current.clanId());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE clan_war_ratings
                SET rating = ?, state_version = ?, updated_at = NOW()
                WHERE clan_id = ? AND state_version = ?
                RETURNING updated_at
                """)) {
            statement.setInt(1, nextRating);
            statement.setLong(2, nextVersion);
            statement.setObject(3, current.clanId());
            statement.setLong(4, current.stateVersion());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ClanWarException("clan-war rating changed concurrently: " + current.clanId());
                return new ClanWarRatingSnapshot(
                        current.clanId(), nextRating, nextVersion, row.getTimestamp("updated_at").toInstant()
                );
            }
        }
    }

    private static ClanWarRatingSnapshot ratingSnapshot(ResultSet row) throws SQLException {
        return new ClanWarRatingSnapshot(
                row.getObject("clan_id", UUID.class),
                row.getInt("rating"),
                row.getLong("state_version"),
                row.getTimestamp("updated_at").toInstant()
        );
    }

    private static void insertResult(
            Connection connection,
            ClanWarSnapshot war,
            UUID operationId,
            UUID winningClanId,
            UUID losingClanId,
            int challengerBefore,
            int challengerAfter,
            int defenderBefore,
            int defenderAfter
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO clan_war_results(
                    war_id,
                    operation_id,
                    winning_clan_id,
                    losing_clan_id,
                    challenger_rating_before,
                    challenger_rating_after,
                    defender_rating_before,
                    defender_rating_after,
                    ruleset_id,
                    ruleset_version,
                    rating_policy_version,
                    rating_k_factor
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, war.warId());
            statement.setObject(2, operationId);
            statement.setObject(3, winningClanId);
            statement.setObject(4, losingClanId);
            statement.setInt(5, challengerBefore);
            statement.setInt(6, challengerAfter);
            statement.setInt(7, defenderBefore);
            statement.setInt(8, defenderAfter);
            statement.setString(9, war.rulesetId());
            statement.setInt(10, war.rulesetVersion());
            statement.setInt(11, war.ratingPolicyVersion());
            statement.setInt(12, war.ratingKFactor());
            statement.executeUpdate();
        }
    }

    private static int ratingTransfer(int winnerRating, int loserRating, int kFactor) {
        double expectedWinner = 1.0d / (1.0d + StrictMath.pow(10.0d, (loserRating - winnerRating) / 400.0d));
        long requested = Math.round(kFactor * (1.0d - expectedWinner));
        long bounded = Math.max(0L, Math.min(requested, loserRating));
        if (bounded > Integer.MAX_VALUE) throw new ClanWarException("Clan-War rating transfer overflow");
        return (int) bounded;
    }

    private static int safeRatingAdd(int rating, int delta) {
        try {
            return Math.addExact(rating, delta);
        } catch (ArithmeticException exception) {
            throw new ClanWarException("Clan-War rating overflow", exception);
        }
    }

    private static UUID deterministicUuid(UUID parentOperationId, UUID itemInstanceId, String purpose) {
        return UUID.nameUUIDFromBytes(
                (parentOperationId + ":" + itemInstanceId + ":" + purpose).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Optional<ProcessedOperation> findProcessed(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type, result::text AS result_json
                FROM processed_operations WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new ProcessedOperation(
                        row.getString("operation_type"), readJsonMap(row.getString("result_json"))
                ));
            }
        }
    }

    private static Object requireReplay(
            ProcessedOperation processed,
            String expectedType,
            Map<String, Object> request,
            UUID operationId
    ) {
        if (!expectedType.equals(processed.operationType())) {
            throw new ClanWarException("operation_id " + operationId + " already belongs to " + processed.operationType());
        }
        if (!ClanWarLifecycleRepository.objectMap(processed.data().get("request"), "request").equals(request)) {
            throw new ClanWarException("operation_id reused with a different Clan-War resolution: " + operationId);
        }
        Object result = processed.data().get("result");
        if (result == null) throw new ClanWarException("processed Clan-War resolution is missing result");
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

    private static Map<String, Object> ratingMap(ClanWarRatingSnapshot rating) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("clan_id", rating.clanId().toString());
        value.put("rating", rating.rating());
        value.put("state_version", rating.stateVersion());
        value.put("updated_at", rating.updatedAt().toString());
        return value;
    }

    private static ClanWarRatingSnapshot ratingFrom(Object raw) {
        Map<String, Object> value = ClanWarLifecycleRepository.objectMap(raw, "rating");
        return new ClanWarRatingSnapshot(
                ClanWarLifecycleRepository.uuidValue(value, "clan_id"),
                ClanWarLifecycleRepository.intValue(value, "rating"),
                ClanWarLifecycleRepository.longValue(value, "state_version"),
                Instant.parse(ClanWarLifecycleRepository.stringValue(value, "updated_at"))
        );
    }

    private static Map<String, Object> completionMap(ClanWarCompletionResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("war", ClanWarLifecycleRepository.warMap(result.war()));
        value.put("losing_clan_id", result.losingClanId().toString());
        value.put("challenger_before", ratingMap(result.challengerBefore()));
        value.put("challenger_after", ratingMap(result.challengerAfter()));
        value.put("defender_before", ratingMap(result.defenderBefore()));
        value.put("defender_after", ratingMap(result.defenderAfter()));
        value.put("return_delivery_ids", result.returnDeliveryIds().stream().map(UUID::toString).toList());
        return value;
    }

    private static ClanWarCompletionResult completionFrom(Object raw) {
        Map<String, Object> value = ClanWarLifecycleRepository.objectMap(raw, "result");
        return new ClanWarCompletionResult(
                ClanWarLifecycleRepository.warFrom(value.get("war")),
                ClanWarLifecycleRepository.uuidValue(value, "losing_clan_id"),
                ratingFrom(value.get("challenger_before")),
                ratingFrom(value.get("challenger_after")),
                ratingFrom(value.get("defender_before")),
                ratingFrom(value.get("defender_after")),
                uuidList(value.get("return_delivery_ids"))
        );
    }

    private static Map<String, Object> terminalMap(ClanWarTerminalResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("war", ClanWarLifecycleRepository.warMap(result.war()));
        value.put("return_delivery_ids", result.returnDeliveryIds().stream().map(UUID::toString).toList());
        return value;
    }

    private static ClanWarTerminalResult terminalFrom(Object raw) {
        Map<String, Object> value = ClanWarLifecycleRepository.objectMap(raw, "result");
        return new ClanWarTerminalResult(
                ClanWarLifecycleRepository.warFrom(value.get("war")),
                uuidList(value.get("return_delivery_ids"))
        );
    }

    private static List<UUID> uuidList(Object raw) {
        if (!(raw instanceof List<?> list)) throw new ClanWarException("Clan-War delivery result is not a list");
        return list.stream().map(value -> UUID.fromString(Objects.toString(value))).toList();
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ClanWarException("Could not parse Clan-War resolution result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ClanWarException("Could not serialize Clan-War resolution result", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedWarItem(
            UUID playerId,
            UUID itemInstanceId,
            long entryItemVersion,
            String locationKind,
            UUID locationId,
            long stateVersion
    ) { }

    private record ProcessedOperation(String operationType, Map<String, Object> data) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            data = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
        }
    }
}
