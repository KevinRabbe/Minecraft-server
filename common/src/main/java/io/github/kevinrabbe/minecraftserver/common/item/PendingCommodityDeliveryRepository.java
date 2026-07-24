package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import io.github.kevinrabbe.minecraftserver.common.session.SessionStatus;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Durable already-owned commodity quantity waiting to be materialized into live Minecraft player state. */
public final class PendingCommodityDeliveryRepository {
    private static final String ISSUE_OPERATION = "PENDING_COMMODITY_DELIVERY_ISSUE";
    private static final String CLAIM_OPERATION = "PENDING_COMMODITY_DELIVERY_CLAIM";
    private static final String LEDGER_ASSET_TYPE = "COMMODITY";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final PlayerStateRepository playerStates;

    public PendingCommodityDeliveryRepository(DataSource dataSource, ItemCatalog itemCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.playerStates = new PlayerStateRepository(dataSource);
    }

    /** Controlled system issuance. Market settlement must create an existing-owned delivery in its own transaction. */
    public PendingCommodityIssueResult issueFromSystem(
            UUID operationId,
            String definitionId,
            UUID recipientPlayerId,
            long quantity,
            String reason,
            UUID actorPlayerId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        String normalizedReason = requireReason(reason);
        ItemDefinition definition = requireCommodityDefinition(definitionId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedIssue> processed = findProcessedIssue(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedIssue previous = processed.orElseThrow();
                    previous.requireSameRequest(
                            definition.definitionId(),
                            recipientPlayerId,
                            quantity,
                            normalizedReason,
                            actorPlayerId,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                requirePlayer(connection, recipientPlayerId);
                requireOptionalPlayer(connection, actorPlayerId);
                UUID deliveryId = UUID.randomUUID();
                insertDelivery(
                        connection,
                        deliveryId,
                        recipientPlayerId,
                        definition.definitionId(),
                        quantity,
                        operationId,
                        normalizedReason
                );
                insertCommodityLedgerCredit(
                        connection,
                        operationId,
                        recipientPlayerId,
                        definition.definitionId(),
                        quantity,
                        normalizedReason
                );

                PendingCommodityIssueResult result = new PendingCommodityIssueResult(
                        deliveryId,
                        recipientPlayerId,
                        definition.definitionId(),
                        quantity
                );
                insertProcessedIssue(
                        connection,
                        operationId,
                        result,
                        normalizedReason,
                        actorPlayerId
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /**
     * Materializes any positive quantity up to the delivery remainder into one fenced player-state snapshot.
     * Ownership was already credited when the delivery was issued, so claim writes no economic ledger entry.
     */
    public PendingCommodityClaimResult claimToPlayerState(
            UUID operationId,
            UUID deliveryId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            long claimQuantity,
            String logicalZoneId,
            String entryPoint,
            byte[] newStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sessionId, "sessionId");
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        if (expectedPlayerStateVersion < 0) {
            throw new IllegalArgumentException("expectedPlayerStateVersion must be >= 0");
        }
        if (claimQuantity <= 0) {
            throw new IllegalArgumentException("claimQuantity must be > 0");
        }
        Objects.requireNonNull(newStatePayload, "newStatePayload");
        String normalizedZone = normalizeOptional(logicalZoneId);
        String normalizedEntry = normalizeOptional(entryPoint);
        String normalizedReason = requireReason(reason);
        String payloadSha256 = sha256(newStatePayload);

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
                            claimQuantity,
                            normalizedZone,
                            normalizedEntry,
                            payloadSha256,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                PendingCommodityDelivery delivery = readDelivery(connection, deliveryId, true);
                requirePending(delivery);
                if (claimQuantity > delivery.remainingQuantity()) {
                    throw new PendingCommodityDeliveryException(
                            "Claim quantity exceeds remaining delivery quantity"
                    );
                }

                lockAndValidateSession(
                        connection,
                        sessionId,
                        delivery.recipientPlayerId(),
                        normalizedBackendId,
                        expectedPlayerStateVersion
                );

                long newPlayerStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion,
                        normalizedZone,
                        normalizedEntry,
                        newStatePayload
                );
                long remainingAfter = delivery.remainingQuantity() - claimQuantity;
                PendingCommodityStatus status = remainingAfter == 0
                        ? PendingCommodityStatus.CLAIMED
                        : PendingCommodityStatus.PENDING;

                insertClaimEvidence(
                        connection,
                        operationId,
                        delivery.deliveryId(),
                        sessionId,
                        normalizedBackendId,
                        claimQuantity,
                        delivery.remainingQuantity(),
                        remainingAfter,
                        newPlayerStateVersion,
                        payloadSha256,
                        normalizedReason
                );
                updateDeliveryAfterClaim(
                        connection,
                        delivery.deliveryId(),
                        delivery.remainingQuantity(),
                        remainingAfter,
                        operationId
                );

                PendingCommodityClaimResult result = new PendingCommodityClaimResult(
                        delivery.deliveryId(),
                        delivery.recipientPlayerId(),
                        delivery.definitionId(),
                        claimQuantity,
                        remainingAfter,
                        newPlayerStateVersion,
                        status
                );
                insertProcessedClaim(
                        connection,
                        operationId,
                        result,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion,
                        normalizedZone,
                        normalizedEntry,
                        payloadSha256,
                        normalizedReason
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public PendingCommodityDelivery load(UUID deliveryId) throws SQLException {
        Objects.requireNonNull(deliveryId, "deliveryId");
        try (Connection connection = dataSource.getConnection()) {
            return readDelivery(connection, deliveryId, false);
        }
    }

    private ItemDefinition requireCommodityDefinition(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
            throw new PendingCommodityDeliveryException(
                    "Pending commodity delivery requires COMMODITY definition: " + definition.definitionId()
            );
        }
        return definition;
    }

    private static PendingCommodityDelivery readDelivery(
            Connection connection,
            UUID deliveryId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT recipient_player_id,
                       definition_id,
                       total_quantity,
                       remaining_quantity,
                       status,
                       issue_operation_id,
                       issue_reason,
                       last_claim_operation_id,
                       created_at,
                       claimed_at
                FROM pending_commodity_deliveries
                WHERE delivery_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, deliveryId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new PendingCommodityDeliveryException("Unknown delivery_id: " + deliveryId);
                }
                Timestamp claimedAt = results.getTimestamp("claimed_at");
                return new PendingCommodityDelivery(
                        deliveryId,
                        results.getObject("recipient_player_id", UUID.class),
                        results.getString("definition_id"),
                        results.getLong("total_quantity"),
                        results.getLong("remaining_quantity"),
                        PendingCommodityStatus.valueOf(results.getString("status")),
                        results.getObject("issue_operation_id", UUID.class),
                        results.getString("issue_reason"),
                        results.getObject("last_claim_operation_id", UUID.class),
                        results.getTimestamp("created_at").toInstant(),
                        claimedAt == null ? null : claimedAt.toInstant()
                );
            }
        }
    }

    private static void requirePending(PendingCommodityDelivery delivery) {
        if (delivery.status() != PendingCommodityStatus.PENDING || delivery.remainingQuantity() <= 0) {
            throw new PendingCommodityDeliveryException("Commodity delivery is fully claimed: " + delivery.deliveryId());
        }
    }

    private static void lockAndValidateSession(
            Connection connection,
            UUID sessionId,
            UUID expectedPlayerId,
            String backendId,
            long expectedStateVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id,
                       owner_backend_id,
                       state_version,
                       status,
                       lease_expires_at IS NOT NULL AND lease_expires_at > NOW() AS lease_valid
                FROM player_sessions
                WHERE network_session_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, sessionId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SessionConflictException("Unknown session: " + sessionId);
                }
                UUID playerId = results.getObject("player_id", UUID.class);
                String ownerBackendId = results.getString("owner_backend_id");
                long stateVersion = results.getLong("state_version");
                SessionStatus status = SessionStatus.valueOf(results.getString("status"));
                boolean leaseValid = results.getBoolean("lease_valid");
                if (!expectedPlayerId.equals(playerId)
                        || !backendId.equals(ownerBackendId)
                        || stateVersion != expectedStateVersion
                        || !leaseValid
                        || (status != SessionStatus.ACTIVE && status != SessionStatus.RECOVERING)) {
                    throw new SessionConflictException(
                            "Commodity delivery claim does not match authoritative live session"
                    );
                }
            }
        }
    }

    private static void insertDelivery(
            Connection connection,
            UUID deliveryId,
            UUID recipientPlayerId,
            String definitionId,
            long quantity,
            UUID issueOperationId,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_commodity_deliveries(
                    delivery_id,
                    recipient_player_id,
                    definition_id,
                    total_quantity,
                    remaining_quantity,
                    issue_operation_id,
                    issue_reason
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, recipientPlayerId);
            statement.setString(3, definitionId);
            statement.setLong(4, quantity);
            statement.setLong(5, quantity);
            statement.setObject(6, issueOperationId);
            statement.setString(7, reason);
            statement.executeUpdate();
        }
    }

    private static void insertClaimEvidence(
            Connection connection,
            UUID operationId,
            UUID deliveryId,
            UUID sessionId,
            String backendId,
            long claimQuantity,
            long remainingBefore,
            long remainingAfter,
            long playerStateVersion,
            String payloadSha256,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_commodity_claims(
                    claim_operation_id,
                    delivery_id,
                    session_id,
                    backend_id,
                    claim_quantity,
                    remaining_before,
                    remaining_after,
                    player_state_version,
                    payload_sha256,
                    reason
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, deliveryId);
            statement.setObject(3, sessionId);
            statement.setString(4, backendId);
            statement.setLong(5, claimQuantity);
            statement.setLong(6, remainingBefore);
            statement.setLong(7, remainingAfter);
            statement.setLong(8, playerStateVersion);
            statement.setString(9, payloadSha256);
            statement.setString(10, reason);
            statement.executeUpdate();
        }
    }

    private static void updateDeliveryAfterClaim(
            Connection connection,
            UUID deliveryId,
            long expectedRemaining,
            long remainingAfter,
            UUID claimOperationId
    ) throws SQLException {
        String sql = remainingAfter == 0
                ? """
                UPDATE pending_commodity_deliveries
                SET remaining_quantity = 0,
                    status = 'CLAIMED',
                    last_claim_operation_id = ?,
                    claimed_at = NOW()
                WHERE delivery_id = ?
                  AND status = 'PENDING'
                  AND remaining_quantity = ?
                """
                : """
                UPDATE pending_commodity_deliveries
                SET remaining_quantity = ?,
                    last_claim_operation_id = ?
                WHERE delivery_id = ?
                  AND status = 'PENDING'
                  AND remaining_quantity = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (remainingAfter == 0) {
                statement.setObject(1, claimOperationId);
                statement.setObject(2, deliveryId);
                statement.setLong(3, expectedRemaining);
            } else {
                statement.setLong(1, remainingAfter);
                statement.setObject(2, claimOperationId);
                statement.setObject(3, deliveryId);
                statement.setLong(4, expectedRemaining);
            }
            if (statement.executeUpdate() != 1) {
                throw new PendingCommodityDeliveryException("Commodity delivery changed concurrently");
            }
        }
    }

    private static void insertCommodityLedgerCredit(
            Connection connection,
            UUID operationId,
            UUID recipientPlayerId,
            String definitionId,
            long quantity,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id,
                    line_no,
                    player_id,
                    asset_type,
                    asset_id,
                    amount,
                    direction,
                    reason
                )
                VALUES (?, 0, ?, ?, ?, ?, 'CREDIT', ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, recipientPlayerId);
            statement.setString(3, LEDGER_ASSET_TYPE);
            statement.setString(4, definitionId);
            statement.setLong(5, quantity);
            statement.setString(6, reason);
            statement.executeUpdate();
        }
    }

    private static void insertProcessedIssue(
            Connection connection,
            UUID operationId,
            PendingCommodityIssueResult result,
            String reason,
            UUID actorPlayerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'delivery_id', ?,
                    'recipient_player_id', ?,
                    'definition_id', ?,
                    'quantity', ?,
                    'reason', ?,
                    'actor_player_id', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, ISSUE_OPERATION);
            statement.setString(3, result.deliveryId().toString());
            statement.setString(4, result.recipientPlayerId().toString());
            statement.setString(5, result.definitionId());
            statement.setLong(6, result.quantity());
            statement.setString(7, reason);
            statement.setString(8, actorPlayerId == null ? null : actorPlayerId.toString());
            statement.executeUpdate();
        }
    }

    private static void insertProcessedClaim(
            Connection connection,
            UUID operationId,
            PendingCommodityClaimResult result,
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
                    'recipient_player_id', ?,
                    'definition_id', ?,
                    'claimed_quantity', ?,
                    'remaining_quantity', ?,
                    'player_state_version', ?,
                    'status', ?,
                    'session_id', ?,
                    'backend_id', ?,
                    'expected_player_state_version', ?,
                    'logical_zone_id', ?,
                    'entry_point', ?,
                    'payload_sha256', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, CLAIM_OPERATION);
            statement.setString(3, result.deliveryId().toString());
            statement.setString(4, result.recipientPlayerId().toString());
            statement.setString(5, result.definitionId());
            statement.setLong(6, result.claimedQuantity());
            statement.setLong(7, result.remainingQuantity());
            statement.setLong(8, result.playerStateVersion());
            statement.setString(9, result.status().name());
            statement.setString(10, sessionId.toString());
            statement.setString(11, backendId);
            statement.setLong(12, expectedPlayerStateVersion);
            statement.setString(13, logicalZoneId);
            statement.setString(14, entryPoint);
            statement.setString(15, payloadSha256);
            statement.setString(16, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedIssue> findProcessedIssue(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'delivery_id' AS delivery_id,
                       result ->> 'recipient_player_id' AS recipient_player_id,
                       result ->> 'definition_id' AS definition_id,
                       result ->> 'quantity' AS quantity,
                       result ->> 'reason' AS reason,
                       result ->> 'actor_player_id' AS actor_player_id
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                requireOperationType(results.getString("operation_type"), ISSUE_OPERATION, operationId);
                try {
                    PendingCommodityIssueResult result = new PendingCommodityIssueResult(
                            UUID.fromString(required(results, "delivery_id")),
                            UUID.fromString(required(results, "recipient_player_id")),
                            required(results, "definition_id"),
                            Long.parseLong(required(results, "quantity"))
                    );
                    String actor = results.getString("actor_player_id");
                    return Optional.of(new ProcessedIssue(
                            result,
                            required(results, "reason"),
                            actor == null ? null : UUID.fromString(actor)
                    ));
                } catch (IllegalArgumentException exception) {
                    throw malformedProcessed(operationId, exception);
                }
            }
        }
    }

    private static Optional<ProcessedClaim> findProcessedClaim(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'delivery_id' AS delivery_id,
                       result ->> 'recipient_player_id' AS recipient_player_id,
                       result ->> 'definition_id' AS definition_id,
                       result ->> 'claimed_quantity' AS claimed_quantity,
                       result ->> 'remaining_quantity' AS remaining_quantity,
                       result ->> 'player_state_version' AS player_state_version,
                       result ->> 'status' AS status,
                       result ->> 'session_id' AS session_id,
                       result ->> 'backend_id' AS backend_id,
                       result ->> 'expected_player_state_version' AS expected_player_state_version,
                       result ->> 'logical_zone_id' AS logical_zone_id,
                       result ->> 'entry_point' AS entry_point,
                       result ->> 'payload_sha256' AS payload_sha256,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                requireOperationType(results.getString("operation_type"), CLAIM_OPERATION, operationId);
                try {
                    PendingCommodityClaimResult result = new PendingCommodityClaimResult(
                            UUID.fromString(required(results, "delivery_id")),
                            UUID.fromString(required(results, "recipient_player_id")),
                            required(results, "definition_id"),
                            Long.parseLong(required(results, "claimed_quantity")),
                            Long.parseLong(required(results, "remaining_quantity")),
                            Long.parseLong(required(results, "player_state_version")),
                            PendingCommodityStatus.valueOf(required(results, "status"))
                    );
                    return Optional.of(new ProcessedClaim(
                            result,
                            UUID.fromString(required(results, "session_id")),
                            required(results, "backend_id"),
                            Long.parseLong(required(results, "expected_player_state_version")),
                            results.getString("logical_zone_id"),
                            results.getString("entry_point"),
                            required(results, "payload_sha256"),
                            required(results, "reason")
                    ));
                } catch (IllegalArgumentException exception) {
                    throw malformedProcessed(operationId, exception);
                }
            }
        }
    }

    private static void requireOperationType(String actual, String expected, UUID operationId) {
        if (!expected.equals(actual)) {
            throw new PendingCommodityDeliveryException(
                    "operation_id already belongs to " + actual + ": " + operationId
            );
        }
    }

    private static String required(ResultSet results, String column) throws SQLException {
        String value = results.getString(column);
        if (value == null || value.isBlank()) {
            throw new PendingCommodityDeliveryException(
                    "Processed commodity delivery operation is missing " + column
            );
        }
        return value;
    }

    private static PendingCommodityDeliveryException malformedProcessed(
            UUID operationId,
            IllegalArgumentException cause
    ) {
        return new PendingCommodityDeliveryException(
                "Malformed persisted commodity delivery operation " + operationId,
                cause
        );
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new PendingCommodityDeliveryException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static void requireOptionalPlayer(Connection connection, UUID playerId) throws SQLException {
        if (playerId != null) {
            requirePlayer(connection, playerId);
        }
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

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record ProcessedIssue(
            PendingCommodityIssueResult result,
            String reason,
            UUID actorPlayerId
    ) {
        void requireSameRequest(
                String definitionId,
                UUID recipientPlayerId,
                long quantity,
                String requestedReason,
                UUID requestedActorPlayerId,
                UUID operationId
        ) {
            if (!result.definitionId().equals(definitionId)
                    || !result.recipientPlayerId().equals(recipientPlayerId)
                    || result.quantity() != quantity
                    || !reason.equals(requestedReason)
                    || !Objects.equals(actorPlayerId, requestedActorPlayerId)) {
                throw new PendingCommodityDeliveryException(
                        "operation_id was already used for a different commodity issuance request: " + operationId
                );
            }
        }
    }

    private record ProcessedClaim(
            PendingCommodityClaimResult result,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            String logicalZoneId,
            String entryPoint,
            String payloadSha256,
            String reason
    ) {
        void requireSameRequest(
                UUID deliveryId,
                UUID requestedSessionId,
                String requestedBackendId,
                long requestedExpectedPlayerStateVersion,
                long requestedClaimQuantity,
                String requestedLogicalZoneId,
                String requestedEntryPoint,
                String requestedPayloadSha256,
                String requestedReason,
                UUID operationId
        ) {
            if (!result.deliveryId().equals(deliveryId)
                    || !sessionId.equals(requestedSessionId)
                    || !backendId.equals(requestedBackendId)
                    || expectedPlayerStateVersion != requestedExpectedPlayerStateVersion
                    || result.claimedQuantity() != requestedClaimQuantity
                    || !Objects.equals(logicalZoneId, requestedLogicalZoneId)
                    || !Objects.equals(entryPoint, requestedEntryPoint)
                    || !payloadSha256.equals(requestedPayloadSha256)
                    || !reason.equals(requestedReason)) {
                throw new PendingCommodityDeliveryException(
                        "operation_id was already used for a different commodity claim request: " + operationId
                );
            }
        }
    }
}
