package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionConflictException;
import io.github.kevinrabbe.minecraftserver.common.session.SessionStatus;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
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

/**
 * Durable correctness buffer for unique items already owned by a player but not yet represented in live inventory.
 */
public final class PendingUniqueDeliveryRepository {
    private static final String ISSUE_OPERATION = "PENDING_UNIQUE_DELIVERY_ISSUE";
    private static final String CLAIM_OPERATION = "PENDING_UNIQUE_DELIVERY_CLAIM";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final PlayerStateRepository playerStates;

    public PendingUniqueDeliveryRepository(DataSource dataSource, ItemCatalog itemCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.playerStates = new PlayerStateRepository(dataSource);
    }

    /**
     * Issues a brand-new individual item into durable pending-delivery custody.
     * Economic ownership is credited exactly once at issuance; claim later only changes custody/representation.
     */
    public PendingUniqueDeliveryIssueResult issueNewIndividual(
            UUID operationId,
            String definitionId,
            UUID recipientPlayerId,
            String reason,
            UUID actorPlayerId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        String normalizedReason = requireReason(reason);
        ItemDefinition definition = requireIndividualDefinition(definitionId);

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
                UUID itemInstanceId = UUID.randomUUID();
                ItemLocation pendingLocation = ItemLocation.pendingDelivery(deliveryId);

                insertDelivery(
                        connection,
                        deliveryId,
                        recipientPlayerId,
                        itemInstanceId,
                        operationId,
                        normalizedReason
                );
                insertItemInstance(
                        connection,
                        itemInstanceId,
                        definition.definitionId(),
                        pendingLocation,
                        recipientPlayerId,
                        operationId,
                        normalizedReason
                );
                insertProvenance(
                        connection,
                        itemInstanceId,
                        0,
                        operationId,
                        "CREATED",
                        null,
                        pendingLocation,
                        normalizedReason,
                        actorPlayerId
                );
                insertLedgerCredit(
                        connection,
                        operationId,
                        recipientPlayerId,
                        itemInstanceId,
                        normalizedReason
                );

                PendingUniqueDeliveryIssueResult result = new PendingUniqueDeliveryIssueResult(
                        deliveryId,
                        recipientPlayerId,
                        itemInstanceId,
                        definition.definitionId(),
                        0
                );
                insertProcessedIssue(connection, operationId, result, normalizedReason, actorPlayerId);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /**
     * Atomically materializes one pending item into the recipient's fenced live player-state snapshot.
     *
     * <p>The supplied payload must already contain the rendered item representation. The caller is the currently
     * owning Paper backend and remains responsible for restoring its local in-memory inventory if this transaction
     * fails. PostgreSQL changes nothing unless player snapshot, item custody, delivery lifecycle, provenance, and
     * idempotency all commit together.</p>
     */
    public PendingUniqueDeliveryClaimResult claimToPlayerState(
            UUID operationId,
            UUID deliveryId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
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
        Objects.requireNonNull(newStatePayload, "newStatePayload");
        String normalizedZoneId = normalizeOptional(logicalZoneId);
        String normalizedEntryPoint = normalizeOptional(entryPoint);
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
                            normalizedZoneId,
                            normalizedEntryPoint,
                            payloadSha256,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                PendingUniqueDelivery observed = readDelivery(connection, deliveryId, false);
                requireClaimableStatus(observed);
                lockAndValidateSession(
                        connection,
                        sessionId,
                        observed.recipientPlayerId(),
                        normalizedBackendId,
                        expectedPlayerStateVersion
                );

                PendingUniqueDelivery delivery = readDelivery(connection, deliveryId, true);
                requireSameDeliveryIdentity(observed, delivery);
                requireClaimableStatus(delivery);

                LockedItem item = lockPendingItem(connection, delivery);
                long newPlayerStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion,
                        normalizedZoneId,
                        normalizedEntryPoint,
                        newStatePayload
                );

                long newItemStateVersion = incrementItemVersion(item.stateVersion(), item.itemInstanceId());
                ItemLocation playerLocation = ItemLocation.playerInventory(delivery.recipientPlayerId());
                updateItemToPlayer(
                        connection,
                        item.itemInstanceId(),
                        item.stateVersion(),
                        newItemStateVersion,
                        delivery.recipientPlayerId()
                );
                insertProvenance(
                        connection,
                        item.itemInstanceId(),
                        newItemStateVersion,
                        operationId,
                        "DELIVERED",
                        ItemLocation.pendingDelivery(delivery.deliveryId()),
                        playerLocation,
                        normalizedReason,
                        delivery.recipientPlayerId()
                );
                markClaimed(connection, delivery.deliveryId(), operationId);

                PendingUniqueDeliveryClaimResult result = new PendingUniqueDeliveryClaimResult(
                        delivery.deliveryId(),
                        delivery.recipientPlayerId(),
                        item.itemInstanceId(),
                        item.definitionId(),
                        newItemStateVersion,
                        newPlayerStateVersion
                );
                insertProcessedClaim(
                        connection,
                        operationId,
                        result,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion,
                        normalizedZoneId,
                        normalizedEntryPoint,
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

    public PendingUniqueDelivery load(UUID deliveryId) throws SQLException {
        Objects.requireNonNull(deliveryId, "deliveryId");
        try (Connection connection = dataSource.getConnection()) {
            return readDelivery(connection, deliveryId, false);
        }
    }

    private ItemDefinition requireIndividualDefinition(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new PendingUniqueDeliveryException(
                    "Pending unique delivery requires an INDIVIDUAL definition: " + definition.definitionId()
            );
        }
        return definition;
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
                            "Pending delivery claim does not match authoritative live session"
                    );
                }
            }
        }
    }

    private static PendingUniqueDelivery readDelivery(
            Connection connection,
            UUID deliveryId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT recipient_player_id,
                       item_instance_id,
                       status,
                       issue_operation_id,
                       claim_operation_id,
                       issue_reason,
                       created_at,
                       claimed_at
                FROM pending_unique_deliveries
                WHERE delivery_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, deliveryId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new PendingUniqueDeliveryException("Unknown delivery_id: " + deliveryId);
                }
                Timestamp claimedAt = results.getTimestamp("claimed_at");
                return new PendingUniqueDelivery(
                        deliveryId,
                        results.getObject("recipient_player_id", UUID.class),
                        results.getObject("item_instance_id", UUID.class),
                        PendingDeliveryStatus.valueOf(results.getString("status")),
                        results.getObject("issue_operation_id", UUID.class),
                        results.getObject("claim_operation_id", UUID.class),
                        results.getString("issue_reason"),
                        results.getTimestamp("created_at").toInstant(),
                        claimedAt == null ? null : claimedAt.toInstant()
                );
            }
        }
    }

    private static void requireSameDeliveryIdentity(
            PendingUniqueDelivery observed,
            PendingUniqueDelivery locked
    ) {
        if (!observed.deliveryId().equals(locked.deliveryId())
                || !observed.recipientPlayerId().equals(locked.recipientPlayerId())
                || !observed.itemInstanceId().equals(locked.itemInstanceId())
                || !observed.issueOperationId().equals(locked.issueOperationId())) {
            throw new PendingUniqueDeliveryException("Pending delivery identity changed concurrently");
        }
    }

    private static void requireClaimableStatus(PendingUniqueDelivery delivery) {
        if (delivery.status() != PendingDeliveryStatus.PENDING) {
            throw new PendingUniqueDeliveryException("Delivery is already claimed: " + delivery.deliveryId());
        }
    }

    private static LockedItem lockPendingItem(
            Connection connection,
            PendingUniqueDelivery delivery
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT definition_id, location_kind, location_id, state_version
                FROM item_instances
                WHERE item_instance_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, delivery.itemInstanceId());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new PendingUniqueDeliveryException(
                            "Pending delivery item authority is missing: " + delivery.itemInstanceId()
                    );
                }
                ItemLocationKind locationKind = ItemLocationKind.valueOf(results.getString("location_kind"));
                UUID locationId = results.getObject("location_id", UUID.class);
                if (locationKind != ItemLocationKind.PENDING_DELIVERY
                        || !delivery.deliveryId().equals(locationId)) {
                    throw new PendingUniqueDeliveryException(
                            "Pending delivery no longer owns authoritative item custody"
                    );
                }
                return new LockedItem(
                        delivery.itemInstanceId(),
                        results.getString("definition_id"),
                        results.getLong("state_version")
                );
            }
        }
    }

    private static void insertDelivery(
            Connection connection,
            UUID deliveryId,
            UUID recipientPlayerId,
            UUID itemInstanceId,
            UUID operationId,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_unique_deliveries(
                    delivery_id,
                    recipient_player_id,
                    item_instance_id,
                    issue_operation_id,
                    issue_reason
                )
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, recipientPlayerId);
            statement.setObject(3, itemInstanceId);
            statement.setObject(4, operationId);
            statement.setString(5, reason);
            statement.executeUpdate();
        }
    }

    private static void insertItemInstance(
            Connection connection,
            UUID itemInstanceId,
            String definitionId,
            ItemLocation location,
            UUID originalOwnerPlayerId,
            UUID operationId,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO item_instances(
                    item_instance_id,
                    definition_id,
                    location_kind,
                    location_id,
                    state_version,
                    original_owner_player_id,
                    created_by_operation_id,
                    created_reason
                )
                VALUES (?, ?, ?, ?, 0, ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setString(2, definitionId);
            statement.setString(3, location.kind().name());
            statement.setObject(4, location.locationId());
            statement.setObject(5, originalOwnerPlayerId);
            statement.setObject(6, operationId);
            statement.setString(7, reason);
            statement.executeUpdate();
        }
    }

    private static void updateItemToPlayer(
            Connection connection,
            UUID itemInstanceId,
            long expectedVersion,
            long newVersion,
            UUID playerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = 'PLAYER_INVENTORY',
                    location_id = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ?
                  AND state_version = ?
                  AND location_kind = 'PENDING_DELIVERY'
                """)) {
            statement.setObject(1, playerId);
            statement.setLong(2, newVersion);
            statement.setObject(3, itemInstanceId);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new PendingUniqueDeliveryException(
                        "Pending delivery item authority changed concurrently"
                );
            }
        }
    }

    private static void insertProvenance(
            Connection connection,
            UUID itemInstanceId,
            long sequenceNo,
            UUID operationId,
            String eventType,
            ItemLocation from,
            ItemLocation to,
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
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, sequenceNo);
            statement.setObject(3, operationId);
            statement.setString(4, eventType);
            statement.setString(5, from == null ? null : from.kind().name());
            statement.setObject(6, from == null ? null : from.locationId());
            statement.setString(7, to.kind().name());
            statement.setObject(8, to.locationId());
            statement.setString(9, reason);
            statement.setObject(10, actorPlayerId);
            statement.executeUpdate();
        }
    }

    private static void insertLedgerCredit(
            Connection connection,
            UUID operationId,
            UUID recipientPlayerId,
            UUID itemInstanceId,
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
                VALUES (?, 0, ?, 'ITEM_INSTANCE', ?, 1, 'CREDIT', ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, recipientPlayerId);
            statement.setString(3, itemInstanceId.toString());
            statement.setString(4, reason);
            statement.executeUpdate();
        }
    }

    private static void markClaimed(
            Connection connection,
            UUID deliveryId,
            UUID claimOperationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE pending_unique_deliveries
                SET status = 'CLAIMED',
                    claim_operation_id = ?,
                    claimed_at = NOW()
                WHERE delivery_id = ?
                  AND status = 'PENDING'
                """)) {
            statement.setObject(1, claimOperationId);
            statement.setObject(2, deliveryId);
            if (statement.executeUpdate() != 1) {
                throw new PendingUniqueDeliveryException("Pending delivery changed concurrently");
            }
        }
    }

    private static void insertProcessedIssue(
            Connection connection,
            UUID operationId,
            PendingUniqueDeliveryIssueResult result,
            String reason,
            UUID actorPlayerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (
                    ?,
                    ?,
                    jsonb_build_object(
                        'delivery_id', ?,
                        'recipient_player_id', ?,
                        'item_instance_id', ?,
                        'definition_id', ?,
                        'item_state_version', ?,
                        'reason', ?,
                        'actor_player_id', ?
                    )
                )
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, ISSUE_OPERATION);
            statement.setString(3, result.deliveryId().toString());
            statement.setString(4, result.recipientPlayerId().toString());
            statement.setString(5, result.itemInstanceId().toString());
            statement.setString(6, result.definitionId());
            statement.setLong(7, result.itemStateVersion());
            statement.setString(8, reason);
            statement.setString(9, nullableUuid(actorPlayerId));
            statement.executeUpdate();
        }
    }

    private static void insertProcessedClaim(
            Connection connection,
            UUID operationId,
            PendingUniqueDeliveryClaimResult result,
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
                VALUES (
                    ?,
                    ?,
                    jsonb_build_object(
                        'delivery_id', ?,
                        'recipient_player_id', ?,
                        'item_instance_id', ?,
                        'definition_id', ?,
                        'item_state_version', ?,
                        'player_state_version', ?,
                        'session_id', ?,
                        'backend_id', ?,
                        'expected_player_state_version', ?,
                        'logical_zone_id', ?,
                        'entry_point', ?,
                        'payload_sha256', ?,
                        'reason', ?
                    )
                )
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, CLAIM_OPERATION);
            statement.setString(3, result.deliveryId().toString());
            statement.setString(4, result.recipientPlayerId().toString());
            statement.setString(5, result.itemInstanceId().toString());
            statement.setString(6, result.definitionId());
            statement.setLong(7, result.itemStateVersion());
            statement.setLong(8, result.playerStateVersion());
            statement.setString(9, sessionId.toString());
            statement.setString(10, backendId);
            statement.setLong(11, expectedPlayerStateVersion);
            statement.setString(12, logicalZoneId);
            statement.setString(13, entryPoint);
            statement.setString(14, payloadSha256);
            statement.setString(15, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedIssue> findProcessedIssue(
            Connection connection,
            UUID operationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'delivery_id' AS delivery_id,
                       result ->> 'recipient_player_id' AS recipient_player_id,
                       result ->> 'item_instance_id' AS item_instance_id,
                       result ->> 'definition_id' AS definition_id,
                       result ->> 'item_state_version' AS item_state_version,
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
                    PendingUniqueDeliveryIssueResult result = new PendingUniqueDeliveryIssueResult(
                            UUID.fromString(required(results, "delivery_id")),
                            UUID.fromString(required(results, "recipient_player_id")),
                            UUID.fromString(required(results, "item_instance_id")),
                            required(results, "definition_id"),
                            Long.parseLong(required(results, "item_state_version"))
                    );
                    return Optional.of(new ProcessedIssue(
                            result,
                            required(results, "reason"),
                            parseNullableUuid(results.getString("actor_player_id"), operationId)
                    ));
                } catch (IllegalArgumentException exception) {
                    throw malformedProcessed(operationId, exception);
                }
            }
        }
    }

    private static Optional<ProcessedClaim> findProcessedClaim(
            Connection connection,
            UUID operationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'delivery_id' AS delivery_id,
                       result ->> 'recipient_player_id' AS recipient_player_id,
                       result ->> 'item_instance_id' AS item_instance_id,
                       result ->> 'definition_id' AS definition_id,
                       result ->> 'item_state_version' AS item_state_version,
                       result ->> 'player_state_version' AS player_state_version,
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
                    PendingUniqueDeliveryClaimResult result = new PendingUniqueDeliveryClaimResult(
                            UUID.fromString(required(results, "delivery_id")),
                            UUID.fromString(required(results, "recipient_player_id")),
                            UUID.fromString(required(results, "item_instance_id")),
                            required(results, "definition_id"),
                            Long.parseLong(required(results, "item_state_version")),
                            Long.parseLong(required(results, "player_state_version"))
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
            throw new PendingUniqueDeliveryException(
                    "operation_id already belongs to " + actual + ": " + operationId
            );
        }
    }

    private static String required(ResultSet results, String column) throws SQLException {
        String value = results.getString(column);
        if (value == null || value.isBlank()) {
            throw new PendingUniqueDeliveryException("Processed delivery operation is missing " + column);
        }
        return value;
    }

    private static UUID parseNullableUuid(String value, UUID operationId) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw malformedProcessed(operationId, exception);
        }
    }

    private static String nullableUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static PendingUniqueDeliveryException malformedProcessed(
            UUID operationId,
            IllegalArgumentException cause
    ) {
        return new PendingUniqueDeliveryException(
                "Malformed persisted pending-delivery operation " + operationId,
                cause
        );
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM players WHERE player_id = ?
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new PendingUniqueDeliveryException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static void requireOptionalPlayer(Connection connection, UUID playerId) throws SQLException {
        if (playerId != null) {
            requirePlayer(connection, playerId);
        }
    }

    private static long incrementItemVersion(long currentVersion, UUID itemInstanceId) {
        try {
            return Math.addExact(currentVersion, 1);
        } catch (ArithmeticException exception) {
            throw new PendingUniqueDeliveryException(
                    "Item state_version overflow: " + itemInstanceId,
                    exception
            );
        }
    }

    private static String sha256(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
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

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedItem(UUID itemInstanceId, String definitionId, long stateVersion) {
    }

    private record ProcessedIssue(
            PendingUniqueDeliveryIssueResult result,
            String reason,
            UUID actorPlayerId
    ) {
        void requireSameRequest(
                String definitionId,
                UUID recipientPlayerId,
                String requestedReason,
                UUID requestedActorPlayerId,
                UUID operationId
        ) {
            if (!result.definitionId().equals(definitionId)
                    || !result.recipientPlayerId().equals(recipientPlayerId)
                    || !reason.equals(requestedReason)
                    || !Objects.equals(actorPlayerId, requestedActorPlayerId)) {
                throw new PendingUniqueDeliveryException(
                        "operation_id was already used for a different pending-delivery issue request: " + operationId
                );
            }
        }
    }

    private record ProcessedClaim(
            PendingUniqueDeliveryClaimResult result,
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
                    || !Objects.equals(logicalZoneId, requestedLogicalZoneId)
                    || !Objects.equals(entryPoint, requestedEntryPoint)
                    || !payloadSha256.equals(requestedPayloadSha256)
                    || !reason.equals(requestedReason)) {
                throw new PendingUniqueDeliveryException(
                        "operation_id was already used for a different pending-delivery claim request: " + operationId
                );
            }
        }
    }
}
