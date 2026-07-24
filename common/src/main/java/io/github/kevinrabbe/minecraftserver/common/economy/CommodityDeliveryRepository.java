package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Atomic claims from durable fungible custody back into one fenced player-state snapshot. */
public final class CommodityDeliveryRepository {
    private static final String CLAIM_OPERATION = "COMMODITY_DELIVERY_CLAIM";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final CommodityStateMutator commodityStateMutator;
    private final PlayerStateRepository playerStates;

    public CommodityDeliveryRepository(DataSource dataSource, CommodityStateMutator commodityStateMutator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.commodityStateMutator = Objects.requireNonNull(commodityStateMutator, "commodityStateMutator");
        this.playerStates = new PlayerStateRepository(dataSource);
    }

    public CommodityDeliverySnapshot load(UUID deliveryId) throws SQLException {
        Objects.requireNonNull(deliveryId, "deliveryId");
        try (Connection connection = dataSource.getConnection()) {
            return readDelivery(connection, deliveryId, false);
        }
    }

    public List<CommodityDeliverySnapshot> listPending(UUID playerId, int limit) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        if (limit <= 0 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT delivery_id,
                            commodity_definition_id,
                            quantity,
                            source_operation_id,
                            status,
                            claim_operation_id,
                            created_at,
                            claimed_at
                     FROM pending_commodity_deliveries
                     WHERE player_id = ? AND status = 'PENDING'
                     ORDER BY created_at ASC, delivery_id ASC
                     LIMIT ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<CommodityDeliverySnapshot> deliveries = new ArrayList<>();
                while (result.next()) {
                    deliveries.add(mapDelivery(result.getObject("delivery_id", UUID.class), playerId, result));
                }
                return List.copyOf(deliveries);
            }
        }
    }

    /**
     * Claims one pending delivery. The adapter-provided next payload is accepted only if the same mutator reproduces
     * the exact addition from the transactionally locked current player state.
     */
    public CommodityDeliveryClaimResult claim(
            UUID operationId,
            UUID deliveryId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            String logicalZoneId,
            String entryPoint,
            byte[] nextPlayerStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(nextPlayerStatePayload, "nextPlayerStatePayload");
        if (expectedPlayerStateVersion < 0) {
            throw new IllegalArgumentException("expectedPlayerStateVersion must be >= 0");
        }
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        String normalizedZoneId = normalizeOptional(logicalZoneId);
        String normalizedEntryPoint = normalizeOptional(entryPoint);
        String normalizedReason = requireReason(reason);
        String payloadSha256 = sha256(nextPlayerStatePayload);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedClaim> processed = findProcessedClaim(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedClaim previous = processed.orElseThrow();
                    previous.requireSameRequest(
                            deliveryId,
                            sessionId,
                            normalizedBackendId,
                            expectedPlayerStateVersion,
                            normalizedZoneId,
                            normalizedEntryPoint,
                            payloadSha256,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                CommodityDeliverySnapshot delivery = readDelivery(connection, deliveryId, true);
                if (delivery.status() != CommodityDeliveryStatus.PENDING) {
                    throw new BazaarException("Commodity delivery is not pending: " + deliveryId);
                }
                UUID sessionPlayerId = playerIdForSession(connection, sessionId);
                if (!delivery.playerId().equals(sessionPlayerId)) {
                    throw new BazaarException("Commodity delivery does not belong to the active session player");
                }

                long nextStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion,
                        normalizedZoneId,
                        normalizedEntryPoint,
                        nextPlayerStatePayload,
                        (lockedPlayerId, currentPayload, nextPayload) -> {
                            if (!lockedPlayerId.equals(delivery.playerId())) {
                                throw new BazaarException("Session player changed during commodity delivery claim");
                            }
                            commodityStateMutator.verifyAddition(
                                    lockedPlayerId,
                                    delivery.commodityDefinitionId(),
                                    delivery.quantity(),
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                markClaimed(connection, deliveryId, operationId);
                CommodityDeliveryClaimResult claimResult = new CommodityDeliveryClaimResult(
                        deliveryId,
                        delivery.playerId(),
                        delivery.commodityDefinitionId(),
                        delivery.quantity(),
                        nextStateVersion
                );
                insertProcessedClaim(
                        connection,
                        operationId,
                        claimResult,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion,
                        normalizedZoneId,
                        normalizedEntryPoint,
                        payloadSha256,
                        normalizedReason
                );
                connection.commit();
                return claimResult;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static CommodityDeliverySnapshot readDelivery(Connection connection, UUID deliveryId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT player_id,
                       commodity_definition_id,
                       quantity,
                       source_operation_id,
                       status,
                       claim_operation_id,
                       created_at,
                       claimed_at
                FROM pending_commodity_deliveries
                WHERE delivery_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, deliveryId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new BazaarException("Unknown commodity delivery: " + deliveryId);
                }
                return mapDelivery(deliveryId, result.getObject("player_id", UUID.class), result);
            }
        }
    }

    private static CommodityDeliverySnapshot mapDelivery(UUID deliveryId, UUID playerId, ResultSet result)
            throws SQLException {
        Timestamp claimedAt = result.getTimestamp("claimed_at");
        return new CommodityDeliverySnapshot(
                deliveryId,
                playerId,
                result.getString("commodity_definition_id"),
                result.getLong("quantity"),
                result.getObject("source_operation_id", UUID.class),
                CommodityDeliveryStatus.valueOf(result.getString("status")),
                result.getObject("claim_operation_id", UUID.class),
                result.getTimestamp("created_at").toInstant(),
                claimedAt == null ? null : claimedAt.toInstant()
        );
    }

    private static UUID playerIdForSession(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id
                FROM player_sessions
                WHERE network_session_id = ?
                """)) {
            statement.setObject(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new BazaarException("Unknown player session: " + sessionId);
                }
                return result.getObject("player_id", UUID.class);
            }
        }
    }

    private static void markClaimed(Connection connection, UUID deliveryId, UUID claimOperationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE pending_commodity_deliveries
                SET status = 'CLAIMED',
                    claim_operation_id = ?,
                    claimed_at = NOW()
                WHERE delivery_id = ? AND status = 'PENDING'
                """)) {
            statement.setObject(1, claimOperationId);
            statement.setObject(2, deliveryId);
            if (statement.executeUpdate() != 1) {
                throw new BazaarException("Commodity delivery changed concurrently: " + deliveryId);
            }
        }
    }

    private static void insertProcessedClaim(
            Connection connection,
            UUID operationId,
            CommodityDeliveryClaimResult result,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            String logicalZoneId,
            String entryPoint,
            String payloadSha256,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'delivery_id', ?,
                    'player_id', ?,
                    'commodity_definition_id', ?,
                    'quantity', ?,
                    'player_state_version', ?,
                    'session_id', ?,
                    'backend_id', ?,
                    'expected_player_state_version', ?,
                    'logical_zone_id', ?,
                    'entry_point', ?,
                    'state_payload_sha256', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, CLAIM_OPERATION);
            statement.setString(3, result.deliveryId().toString());
            statement.setString(4, result.playerId().toString());
            statement.setString(5, result.commodityDefinitionId());
            statement.setLong(6, result.quantity());
            statement.setLong(7, result.playerStateVersion());
            statement.setString(8, sessionId.toString());
            statement.setString(9, backendId);
            statement.setLong(10, expectedPlayerStateVersion);
            setNullableString(statement, 11, logicalZoneId);
            setNullableString(statement, 12, entryPoint);
            statement.setString(13, payloadSha256);
            statement.setString(14, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedClaim> findProcessedClaim(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'delivery_id' AS delivery_id,
                       result ->> 'player_id' AS player_id,
                       result ->> 'commodity_definition_id' AS commodity_definition_id,
                       result ->> 'quantity' AS quantity,
                       result ->> 'player_state_version' AS player_state_version,
                       result ->> 'session_id' AS session_id,
                       result ->> 'backend_id' AS backend_id,
                       result ->> 'expected_player_state_version' AS expected_player_state_version,
                       result ->> 'logical_zone_id' AS logical_zone_id,
                       result ->> 'entry_point' AS entry_point,
                       result ->> 'state_payload_sha256' AS state_payload_sha256,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                requireOperationType(row.getString("operation_type"), CLAIM_OPERATION, operationId);
                CommodityDeliveryClaimResult result = new CommodityDeliveryClaimResult(
                        UUID.fromString(requireField(row, "delivery_id")),
                        UUID.fromString(requireField(row, "player_id")),
                        requireField(row, "commodity_definition_id"),
                        Long.parseLong(requireField(row, "quantity")),
                        Long.parseLong(requireField(row, "player_state_version"))
                );
                return Optional.of(new ProcessedClaim(
                        result,
                        UUID.fromString(requireField(row, "session_id")),
                        requireField(row, "backend_id"),
                        Long.parseLong(requireField(row, "expected_player_state_version")),
                        row.getString("logical_zone_id"),
                        row.getString("entry_point"),
                        requireField(row, "state_payload_sha256"),
                        requireField(row, "reason")
                ));
            } catch (IllegalArgumentException exception) {
                throw new BazaarException("Invalid processed commodity-delivery result for " + operationId, exception);
            }
        }
    }

    private static String requireField(ResultSet result, String field) throws SQLException {
        String value = result.getString(field);
        if (value == null) {
            throw new BazaarException("Processed commodity-delivery result is missing field: " + field);
        }
        return value;
    }

    private static void requireOperationType(String actual, String expected, UUID operationId) {
        if (!expected.equals(actual)) {
            throw new BazaarException(
                    "operation_id " + operationId + " already belongs to operation type " + actual
            );
        }
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String normalized = reason.trim();
        if (!REASON_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("reason must be a stable lowercase identifier: " + normalized);
        }
        return normalized;
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private record ProcessedClaim(
            CommodityDeliveryClaimResult result,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            String logicalZoneId,
            String entryPoint,
            String payloadSha256,
            String reason
    ) {
        private void requireSameRequest(
                UUID deliveryId,
                UUID expectedSessionId,
                String expectedBackendId,
                long expectedVersion,
                String expectedZoneId,
                String expectedEntryPoint,
                String expectedPayloadSha256,
                String expectedReason,
                UUID operationId
        ) {
            if (!result.deliveryId().equals(deliveryId)
                    || !sessionId.equals(expectedSessionId)
                    || !backendId.equals(expectedBackendId)
                    || expectedPlayerStateVersion != expectedVersion
                    || !Objects.equals(logicalZoneId, expectedZoneId)
                    || !Objects.equals(entryPoint, expectedEntryPoint)
                    || !payloadSha256.equals(expectedPayloadSha256)
                    || !reason.equals(expectedReason)) {
                throw new BazaarException("operation_id reused with different commodity claim request: " + operationId);
            }
        }
    }
}
