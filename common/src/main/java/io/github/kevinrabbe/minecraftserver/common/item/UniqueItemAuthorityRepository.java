package io.github.kevinrabbe.minecraftserver.common.item;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL authority for individual item identity and custody.
 *
 * <p>Every mutation is one database transaction, version-fenced, provenance-appended, ledgered where player
 * ownership changes, and protected by the shared processed_operations idempotency table.</p>
 */
public final class UniqueItemAuthorityRepository {
    private static final String CREATE_OPERATION = "ITEM_INSTANCE_CREATE";
    private static final String MOVE_OPERATION = "ITEM_INSTANCE_MOVE";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;

    public UniqueItemAuthorityRepository(DataSource dataSource, ItemCatalog itemCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
    }

    public UniqueItemAuthorityResult createForPlayer(
            UUID operationId,
            String definitionId,
            UUID ownerPlayerId,
            String reason,
            UUID actorPlayerId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        String normalizedReason = requireReason(reason);
        ItemDefinition definition = requireIndividualDefinition(definitionId);
        ItemLocation target = ItemLocation.playerInventory(ownerPlayerId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<UniqueItemAuthorityResult> processed = findProcessedResult(
                        connection,
                        operationId,
                        CREATE_OPERATION
                );
                if (processed.isPresent()) {
                    UniqueItemAuthorityResult previous = processed.orElseThrow();
                    requireSameCreateRequest(previous, definition.definitionId(), target);
                    connection.commit();
                    return previous;
                }

                requirePlayer(connection, ownerPlayerId);
                requireOptionalPlayer(connection, actorPlayerId);

                UUID itemInstanceId = UUID.randomUUID();
                insertItemInstance(
                        connection,
                        itemInstanceId,
                        definition.definitionId(),
                        target,
                        ownerPlayerId,
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
                        target,
                        normalizedReason,
                        actorPlayerId
                );
                insertLedgerLine(
                        connection,
                        operationId,
                        0,
                        ownerPlayerId,
                        itemInstanceId,
                        "CREDIT",
                        normalizedReason
                );

                UniqueItemAuthorityResult result = new UniqueItemAuthorityResult(
                        itemInstanceId,
                        definition.definitionId(),
                        0,
                        target
                );
                insertProcessedResult(connection, operationId, CREATE_OPERATION, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public UniqueItemAuthorityResult move(
            UUID operationId,
            UUID itemInstanceId,
            long expectedStateVersion,
            ItemLocation expectedLocation,
            ItemLocation targetLocation,
            String reason,
            UUID actorPlayerId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        Objects.requireNonNull(expectedLocation, "expectedLocation");
        Objects.requireNonNull(targetLocation, "targetLocation");
        if (expectedStateVersion < 0) {
            throw new IllegalArgumentException("expectedStateVersion must be >= 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<UniqueItemAuthorityResult> processed = findProcessedResult(
                        connection,
                        operationId,
                        MOVE_OPERATION
                );
                if (processed.isPresent()) {
                    UniqueItemAuthorityResult previous = processed.orElseThrow();
                    if (!previous.itemInstanceId().equals(itemInstanceId)
                            || !previous.location().equals(targetLocation)) {
                        throw new UniqueItemAuthorityException(
                                "operation_id was already used for a different unique-item move: " + operationId
                        );
                    }
                    connection.commit();
                    return previous;
                }

                UniqueItemInstance current = lockItem(connection, itemInstanceId);
                requireIndividualDefinition(current.definitionId());
                if (current.location().kind() == ItemLocationKind.DESTROYED) {
                    throw new UniqueItemAuthorityException("Destroyed item cannot move: " + itemInstanceId);
                }
                if (current.stateVersion() != expectedStateVersion) {
                    throw new UniqueItemAuthorityException(
                            "Stale item state_version for " + itemInstanceId
                                    + ": expected " + expectedStateVersion
                                    + " but authoritative version is " + current.stateVersion()
                    );
                }
                if (!current.location().equals(expectedLocation)) {
                    throw new UniqueItemAuthorityException(
                            "Item location no longer matches expected authority for " + itemInstanceId
                    );
                }
                if (current.location().equals(targetLocation)) {
                    throw new UniqueItemAuthorityException("Unique-item move must change location: " + itemInstanceId);
                }

                requireLocationPlayer(connection, targetLocation);
                requireOptionalPlayer(connection, actorPlayerId);

                long nextVersion;
                try {
                    nextVersion = Math.addExact(current.stateVersion(), 1);
                } catch (ArithmeticException exception) {
                    throw new UniqueItemAuthorityException("Item state_version overflow: " + itemInstanceId, exception);
                }

                updateItemLocation(
                        connection,
                        itemInstanceId,
                        current.stateVersion(),
                        nextVersion,
                        targetLocation
                );
                insertProvenance(
                        connection,
                        itemInstanceId,
                        nextVersion,
                        operationId,
                        provenanceEventType(current.location(), targetLocation),
                        current.location(),
                        targetLocation,
                        normalizedReason,
                        actorPlayerId
                );
                appendMovementLedger(
                        connection,
                        operationId,
                        itemInstanceId,
                        current.location(),
                        targetLocation,
                        normalizedReason
                );

                UniqueItemAuthorityResult result = new UniqueItemAuthorityResult(
                        itemInstanceId,
                        current.definitionId(),
                        nextVersion,
                        targetLocation
                );
                insertProcessedResult(connection, operationId, MOVE_OPERATION, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public UniqueItemInstance load(UUID itemInstanceId) throws SQLException {
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        try (Connection connection = dataSource.getConnection()) {
            return readItem(connection, itemInstanceId, false);
        }
    }

    private ItemDefinition requireIndividualDefinition(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new UniqueItemAuthorityException(
                    "Only INDIVIDUAL definitions may receive item_instance_id: " + definition.definitionId()
            );
        }
        return definition;
    }

    private static void requireSameCreateRequest(
            UniqueItemAuthorityResult previous,
            String definitionId,
            ItemLocation target
    ) {
        if (!previous.definitionId().equals(definitionId) || !previous.location().equals(target)) {
            throw new UniqueItemAuthorityException(
                    "operation_id was already used for a different unique-item creation request"
            );
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

    private static void requireLocationPlayer(Connection connection, ItemLocation location) throws SQLException {
        if (location.kind() == ItemLocationKind.PLAYER_INVENTORY) {
            requirePlayer(connection, location.locationId());
        }
    }

    private static void requireOptionalPlayer(Connection connection, UUID playerId) throws SQLException {
        if (playerId != null) {
            requirePlayer(connection, playerId);
        }
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM players
                WHERE player_id = ?
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new UniqueItemAuthorityException("Unknown player_id used as item authority: " + playerId);
                }
            }
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

    private static void updateItemLocation(
            Connection connection,
            UUID itemInstanceId,
            long expectedVersion,
            long nextVersion,
            ItemLocation targetLocation
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = ?,
                    location_id = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ?
                  AND state_version = ?
                """)) {
            statement.setString(1, targetLocation.kind().name());
            statement.setObject(2, targetLocation.locationId());
            statement.setLong(3, nextVersion);
            statement.setObject(4, itemInstanceId);
            statement.setLong(5, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new UniqueItemAuthorityException(
                        "Unique-item authority changed concurrently: " + itemInstanceId
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

    private static void appendMovementLedger(
            Connection connection,
            UUID operationId,
            UUID itemInstanceId,
            ItemLocation from,
            ItemLocation to,
            String reason
    ) throws SQLException {
        int lineNo = 0;
        if (from.kind() == ItemLocationKind.PLAYER_INVENTORY) {
            insertLedgerLine(
                    connection,
                    operationId,
                    lineNo++,
                    from.locationId(),
                    itemInstanceId,
                    "DEBIT",
                    reason
            );
        }
        if (to.kind() == ItemLocationKind.PLAYER_INVENTORY) {
            insertLedgerLine(
                    connection,
                    operationId,
                    lineNo,
                    to.locationId(),
                    itemInstanceId,
                    "CREDIT",
                    reason
            );
        }
    }

    private static void insertLedgerLine(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            UUID itemInstanceId,
            String direction,
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
                VALUES (?, ?, ?, 'ITEM_INSTANCE', ?, 1, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            statement.setObject(3, playerId);
            statement.setString(4, itemInstanceId.toString());
            statement.setString(5, direction);
            statement.setString(6, reason);
            statement.executeUpdate();
        }
    }

    private static void insertProcessedResult(
            Connection connection,
            UUID operationId,
            String operationType,
            UniqueItemAuthorityResult result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (
                    ?,
                    ?,
                    jsonb_build_object(
                        'item_instance_id', ?,
                        'definition_id', ?,
                        'state_version', ?,
                        'location_kind', ?,
                        'location_id', ?
                    )
                )
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, result.itemInstanceId().toString());
            statement.setString(4, result.definitionId());
            statement.setLong(5, result.stateVersion());
            statement.setString(6, result.location().kind().name());
            statement.setString(
                    7,
                    result.location().locationId() == null ? null : result.location().locationId().toString()
            );
            statement.executeUpdate();
        }
    }

    private static Optional<UniqueItemAuthorityResult> findProcessedResult(
            Connection connection,
            UUID operationId,
            String expectedOperationType
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'item_instance_id' AS item_instance_id,
                       result ->> 'definition_id' AS definition_id,
                       result ->> 'state_version' AS state_version,
                       result ->> 'location_kind' AS location_kind,
                       result ->> 'location_id' AS location_id
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }

                String operationType = results.getString("operation_type");
                if (!expectedOperationType.equals(operationType)) {
                    throw new UniqueItemAuthorityException(
                            "operation_id already belongs to " + operationType + ": " + operationId
                    );
                }

                try {
                    UUID itemInstanceId = UUID.fromString(requireResultField(results, "item_instance_id"));
                    String definitionId = requireResultField(results, "definition_id");
                    long stateVersion = Long.parseLong(requireResultField(results, "state_version"));
                    ItemLocationKind locationKind = ItemLocationKind.valueOf(
                            requireResultField(results, "location_kind")
                    );
                    String rawLocationId = results.getString("location_id");
                    UUID locationId = rawLocationId == null ? null : UUID.fromString(rawLocationId);
                    return Optional.of(new UniqueItemAuthorityResult(
                            itemInstanceId,
                            definitionId,
                            stateVersion,
                            new ItemLocation(locationKind, locationId)
                    ));
                } catch (IllegalArgumentException exception) {
                    throw new UniqueItemAuthorityException(
                            "Malformed persisted idempotency result for operation_id " + operationId,
                            exception
                    );
                }
            }
        }
    }

    private static String requireResultField(ResultSet results, String column) throws SQLException {
        String value = results.getString(column);
        if (value == null || value.isBlank()) {
            throw new UniqueItemAuthorityException("Processed operation result is missing " + column);
        }
        return value;
    }

    private UniqueItemInstance lockItem(Connection connection, UUID itemInstanceId) throws SQLException {
        return readItem(connection, itemInstanceId, true);
    }

    private UniqueItemInstance readItem(
            Connection connection,
            UUID itemInstanceId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT definition_id,
                       location_kind,
                       location_id,
                       state_version,
                       original_owner_player_id,
                       created_by_operation_id,
                       created_reason,
                       created_at,
                       updated_at
                FROM item_instances
                WHERE item_instance_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new UniqueItemAuthorityException("Unknown item_instance_id: " + itemInstanceId);
                }
                ItemLocation location = new ItemLocation(
                        ItemLocationKind.valueOf(results.getString("location_kind")),
                        results.getObject("location_id", UUID.class)
                );
                Timestamp createdAt = results.getTimestamp("created_at");
                Timestamp updatedAt = results.getTimestamp("updated_at");
                return new UniqueItemInstance(
                        itemInstanceId,
                        results.getString("definition_id"),
                        location,
                        results.getLong("state_version"),
                        results.getObject("original_owner_player_id", UUID.class),
                        results.getObject("created_by_operation_id", UUID.class),
                        results.getString("created_reason"),
                        createdAt.toInstant(),
                        updatedAt.toInstant()
                );
            }
        }
    }

    private static String provenanceEventType(ItemLocation from, ItemLocation to) {
        if (to.kind() == ItemLocationKind.QUARANTINE) {
            return "QUARANTINED";
        }
        if (to.kind() == ItemLocationKind.DESTROYED) {
            return "DESTROYED";
        }
        if (from.kind() == ItemLocationKind.QUARANTINE
                && to.kind() == ItemLocationKind.PLAYER_INVENTORY) {
            return "RECOVERED";
        }
        return "MOVED";
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
