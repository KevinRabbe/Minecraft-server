package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Trusted mutation authority for unique-item upgrade state.
 *
 * <p>This layer deliberately owns no player-facing cost, success chance, material requirement, or stat curve. It only
 * commits one already-authorized upgrade step atomically and replay-safely.</p>
 */
public final class ItemUpgradeRepository {
    private static final String OPERATION_TYPE = "ITEM_UPGRADE";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;

    public ItemUpgradeRepository(DataSource dataSource, ItemCatalog itemCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
    }

    public ItemUpgradeResult upgradeOneLevel(
            UUID operationId,
            UUID itemInstanceId,
            long expectedStateVersion,
            ItemLocation expectedLocation,
            int expectedUpgradeLevel,
            String reason,
            UUID actorPlayerId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        Objects.requireNonNull(expectedLocation, "expectedLocation");
        if (expectedStateVersion < 0) {
            throw new IllegalArgumentException("expectedStateVersion must be >= 0");
        }
        new UpgradeState(expectedUpgradeLevel);
        if (expectedLocation.kind() != ItemLocationKind.PLAYER_INVENTORY) {
            throw new IllegalArgumentException("unique-item upgrades require PLAYER_INVENTORY custody");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedUpgrade> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedUpgrade previous = processed.orElseThrow();
                    previous.requireSameRequest(
                            itemInstanceId,
                            expectedStateVersion,
                            expectedLocation,
                            expectedUpgradeLevel,
                            normalizedReason,
                            actorPlayerId,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                Head current = lockHead(connection, itemInstanceId);
                ItemDefinition definition = itemCatalog.require(current.definitionId());
                if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
                    throw new UniqueItemAuthorityException(
                            "Only INDIVIDUAL definitions can be upgraded: " + current.definitionId()
                    );
                }
                if (current.stateVersion() != expectedStateVersion) {
                    throw new UniqueItemAuthorityException(
                            "Stale item state_version for upgrade " + itemInstanceId
                                    + ": expected " + expectedStateVersion
                                    + " but authoritative version is " + current.stateVersion()
                    );
                }
                if (!current.location().equals(expectedLocation)) {
                    throw new UniqueItemAuthorityException(
                            "Item location no longer matches expected upgrade custody for " + itemInstanceId
                    );
                }
                if (current.upgradeLevel() != expectedUpgradeLevel) {
                    throw new UniqueItemAuthorityException(
                            "Stale upgrade level for " + itemInstanceId
                                    + ": expected " + expectedUpgradeLevel
                                    + " but authoritative level is " + current.upgradeLevel()
                    );
                }
                if (current.location().kind() != ItemLocationKind.PLAYER_INVENTORY) {
                    throw new UniqueItemAuthorityException(
                            "Unique item cannot be upgraded outside player inventory custody: " + itemInstanceId
                    );
                }
                requireOptionalPlayer(connection, actorPlayerId);

                int nextUpgradeLevel;
                long nextStateVersion;
                try {
                    nextUpgradeLevel = Math.addExact(current.upgradeLevel(), 1);
                    nextStateVersion = Math.addExact(current.stateVersion(), 1);
                } catch (ArithmeticException exception) {
                    throw new UniqueItemAuthorityException("Item upgrade/version overflow: " + itemInstanceId, exception);
                }
                new UpgradeState(nextUpgradeLevel);

                updateUpgradeHead(
                        connection,
                        itemInstanceId,
                        current.stateVersion(),
                        current.upgradeLevel(),
                        nextStateVersion,
                        nextUpgradeLevel
                );
                insertUpgradeEvent(
                        connection,
                        operationId,
                        itemInstanceId,
                        current.stateVersion(),
                        nextStateVersion,
                        current.upgradeLevel(),
                        nextUpgradeLevel,
                        normalizedReason,
                        actorPlayerId
                );
                insertUpgradeProvenance(
                        connection,
                        operationId,
                        itemInstanceId,
                        nextStateVersion,
                        current.location(),
                        normalizedReason,
                        actorPlayerId
                );

                ItemUpgradeResult result = new ItemUpgradeResult(
                        itemInstanceId,
                        current.definitionId(),
                        current.upgradeLevel(),
                        nextUpgradeLevel,
                        current.stateVersion(),
                        nextStateVersion,
                        current.location(),
                        normalizedReason
                );
                insertProcessed(connection, operationId, result, actorPlayerId);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static Head lockHead(Connection connection, UUID itemInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT definition_id, location_kind, location_id, state_version, upgrade_level
                FROM item_instances
                WHERE item_instance_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new UniqueItemAuthorityException("Unknown item_instance_id: " + itemInstanceId);
                }
                return new Head(
                        row.getString("definition_id"),
                        new ItemLocation(
                                ItemLocationKind.valueOf(row.getString("location_kind")),
                                row.getObject("location_id", UUID.class)
                        ),
                        row.getLong("state_version"),
                        row.getInt("upgrade_level")
                );
            }
        }
    }

    private static void updateUpgradeHead(
            Connection connection,
            UUID itemInstanceId,
            long expectedStateVersion,
            int expectedUpgradeLevel,
            long nextStateVersion,
            int nextUpgradeLevel
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET upgrade_level = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ?
                  AND state_version = ?
                  AND upgrade_level = ?
                  AND location_kind = 'PLAYER_INVENTORY'
                """)) {
            statement.setInt(1, nextUpgradeLevel);
            statement.setLong(2, nextStateVersion);
            statement.setObject(3, itemInstanceId);
            statement.setLong(4, expectedStateVersion);
            statement.setInt(5, expectedUpgradeLevel);
            if (statement.executeUpdate() != 1) {
                throw new UniqueItemAuthorityException(
                        "Unique-item upgrade authority changed concurrently: " + itemInstanceId
                );
            }
        }
    }

    private static void insertUpgradeEvent(
            Connection connection,
            UUID operationId,
            UUID itemInstanceId,
            long fromStateVersion,
            long toStateVersion,
            int fromUpgradeLevel,
            int toUpgradeLevel,
            String reason,
            UUID actorPlayerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO item_upgrade_events(
                    item_instance_id,
                    operation_id,
                    from_state_version,
                    to_state_version,
                    from_upgrade_level,
                    to_upgrade_level,
                    reason,
                    actor_player_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setObject(2, operationId);
            statement.setLong(3, fromStateVersion);
            statement.setLong(4, toStateVersion);
            statement.setInt(5, fromUpgradeLevel);
            statement.setInt(6, toUpgradeLevel);
            statement.setString(7, reason);
            statement.setObject(8, actorPlayerId);
            statement.executeUpdate();
        }
    }

    private static void insertUpgradeProvenance(
            Connection connection,
            UUID operationId,
            UUID itemInstanceId,
            long stateVersion,
            ItemLocation location,
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
                ) VALUES (?, ?, ?, 'UPGRADED', ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, stateVersion);
            statement.setObject(3, operationId);
            statement.setString(4, location.kind().name());
            statement.setObject(5, location.locationId());
            statement.setString(6, location.kind().name());
            statement.setObject(7, location.locationId());
            statement.setString(8, reason);
            statement.setObject(9, actorPlayerId);
            statement.executeUpdate();
        }
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            ItemUpgradeResult result,
            UUID actorPlayerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (
                    ?,
                    ?,
                    jsonb_build_object(
                        'item_instance_id', ?,
                        'definition_id', ?,
                        'from_upgrade_level', ?,
                        'to_upgrade_level', ?,
                        'from_state_version', ?,
                        'to_state_version', ?,
                        'location_kind', ?,
                        'location_id', ?,
                        'reason', ?,
                        'actor_player_id', ?
                    )
                )
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, OPERATION_TYPE);
            statement.setString(3, result.itemInstanceId().toString());
            statement.setString(4, result.definitionId());
            statement.setInt(5, result.fromUpgradeLevel());
            statement.setInt(6, result.toUpgradeLevel());
            statement.setLong(7, result.fromStateVersion());
            statement.setLong(8, result.toStateVersion());
            statement.setString(9, result.location().kind().name());
            statement.setString(10, nullableUuid(result.location().locationId()));
            statement.setString(11, result.reason());
            statement.setString(12, nullableUuid(actorPlayerId));
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedUpgrade> findProcessed(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'item_instance_id' AS item_instance_id,
                       result ->> 'definition_id' AS definition_id,
                       result ->> 'from_upgrade_level' AS from_upgrade_level,
                       result ->> 'to_upgrade_level' AS to_upgrade_level,
                       result ->> 'from_state_version' AS from_state_version,
                       result ->> 'to_state_version' AS to_state_version,
                       result ->> 'location_kind' AS location_kind,
                       result ->> 'location_id' AS location_id,
                       result ->> 'reason' AS reason,
                       result ->> 'actor_player_id' AS actor_player_id
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                String operationType = row.getString("operation_type");
                if (!OPERATION_TYPE.equals(operationType)) {
                    throw new UniqueItemAuthorityException(
                            "operation_id already belongs to " + operationType + ": " + operationId
                    );
                }
                try {
                    ItemUpgradeResult result = new ItemUpgradeResult(
                            UUID.fromString(requireField(row, "item_instance_id")),
                            requireField(row, "definition_id"),
                            Integer.parseInt(requireField(row, "from_upgrade_level")),
                            Integer.parseInt(requireField(row, "to_upgrade_level")),
                            Long.parseLong(requireField(row, "from_state_version")),
                            Long.parseLong(requireField(row, "to_state_version")),
                            new ItemLocation(
                                    ItemLocationKind.valueOf(requireField(row, "location_kind")),
                                    parseNullableUuid(row.getString("location_id"), operationId, "location_id")
                            ),
                            requireField(row, "reason")
                    );
                    return Optional.of(new ProcessedUpgrade(
                            result,
                            parseNullableUuid(row.getString("actor_player_id"), operationId, "actor_player_id")
                    ));
                } catch (IllegalArgumentException exception) {
                    throw new UniqueItemAuthorityException(
                            "Malformed persisted item-upgrade result for operation_id " + operationId,
                            exception
                    );
                }
            }
        }
    }

    private static void requireOptionalPlayer(Connection connection, UUID playerId) throws SQLException {
        if (playerId == null) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new UniqueItemAuthorityException("Unknown actor player_id for item upgrade: " + playerId);
                }
            }
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

    private static String requireField(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        if (value == null || value.isBlank()) {
            throw new UniqueItemAuthorityException("Processed item-upgrade result is missing " + column);
        }
        return value;
    }

    private static UUID parseNullableUuid(String value, UUID operationId, String fieldName) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new UniqueItemAuthorityException(
                    "Malformed " + fieldName + " in processed item-upgrade operation " + operationId,
                    exception
            );
        }
    }

    private static String nullableUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record Head(
            String definitionId,
            ItemLocation location,
            long stateVersion,
            int upgradeLevel
    ) { }

    private record ProcessedUpgrade(ItemUpgradeResult result, UUID actorPlayerId) {
        void requireSameRequest(
                UUID itemInstanceId,
                long expectedStateVersion,
                ItemLocation expectedLocation,
                int expectedUpgradeLevel,
                String reason,
                UUID actorPlayerId,
                UUID operationId
        ) {
            if (!result.itemInstanceId().equals(itemInstanceId)
                    || result.fromStateVersion() != expectedStateVersion
                    || !result.location().equals(expectedLocation)
                    || result.fromUpgradeLevel() != expectedUpgradeLevel
                    || !result.reason().equals(reason)
                    || !Objects.equals(this.actorPlayerId, actorPlayerId)) {
                throw new UniqueItemAuthorityException(
                        "operation_id was already used for a different item-upgrade request: " + operationId
                );
            }
        }
    }
}
