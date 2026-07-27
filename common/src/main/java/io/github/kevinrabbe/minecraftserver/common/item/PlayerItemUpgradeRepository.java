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
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Atomic single-writer authority for upgrading an item that is represented inside live serialized player state.
 *
 * <p>This layer owns no cost, eligibility policy, success chance, or stat curve. It only commits one already-authorized
 * upgrade step together with the exact ItemStack authority-version transition in the player's fenced state.</p>
 */
public final class PlayerItemUpgradeRepository {
    private static final String OPERATION_TYPE = "PLAYER_ITEM_UPGRADE";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final PlayerStateRepository playerStates;
    private final PlayerItemUpgradeStateValidator stateValidator;

    public PlayerItemUpgradeRepository(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            PlayerItemUpgradeStateValidator stateValidator
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.playerStates = new PlayerStateRepository(dataSource);
        this.stateValidator = Objects.requireNonNull(stateValidator, "stateValidator");
    }

    public PlayerItemUpgradeResult upgradeOneLevel(
            UUID operationId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            UUID itemInstanceId,
            long expectedItemStateVersion,
            int expectedUpgradeLevel,
            String logicalZoneId,
            String entryPoint,
            byte[] nextStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        if (expectedPlayerStateVersion < 0 || expectedItemStateVersion < 0) {
            throw new IllegalArgumentException("expected state versions must be >= 0");
        }
        new UpgradeState(expectedUpgradeLevel);
        Objects.requireNonNull(nextStatePayload, "nextStatePayload");
        String normalizedZone = normalizeOptional(logicalZoneId);
        String normalizedEntry = normalizeOptional(entryPoint);
        String normalizedReason = requireReason(reason);
        String payloadSha256 = sha256(nextStatePayload);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedPlayerUpgrade> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedPlayerUpgrade previous = processed.orElseThrow();
                    previous.requireSameRequest(
                            sessionId,
                            normalizedBackendId,
                            expectedPlayerStateVersion,
                            itemInstanceId,
                            expectedItemStateVersion,
                            expectedUpgradeLevel,
                            normalizedZone,
                            normalizedEntry,
                            payloadSha256,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                LiveSession session = lockLiveSession(
                        connection,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion
                );
                Head current = lockHead(connection, itemInstanceId);
                ItemDefinition definition = itemCatalog.require(current.definitionId());
                if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
                    throw new UniqueItemAuthorityException(
                            "Only INDIVIDUAL definitions can be upgraded: " + current.definitionId()
                    );
                }
                if (definition.category() != ItemCategory.EQUIPMENT) {
                    throw new UniqueItemAuthorityException(
                            "Only EQUIPMENT definitions can be upgraded: " + current.definitionId()
                    );
                }
                if (current.stateVersion() != expectedItemStateVersion) {
                    throw new UniqueItemAuthorityException(
                            "Stale item state_version for carried upgrade " + itemInstanceId
                                    + ": expected " + expectedItemStateVersion
                                    + " but authoritative version is " + current.stateVersion()
                    );
                }
                if (current.upgradeLevel() != expectedUpgradeLevel) {
                    throw new UniqueItemAuthorityException(
                            "Stale upgrade level for " + itemInstanceId
                                    + ": expected " + expectedUpgradeLevel
                                    + " but authoritative level is " + current.upgradeLevel()
                    );
                }
                ItemLocation playerLocation = ItemLocation.playerInventory(session.playerId());
                if (!playerLocation.equals(current.location())) {
                    throw new UniqueItemAuthorityException(
                            "Live session player does not own authoritative item inventory custody: " + itemInstanceId
                    );
                }

                int nextUpgradeLevel;
                long nextItemStateVersion;
                try {
                    nextUpgradeLevel = Math.addExact(current.upgradeLevel(), 1);
                    nextItemStateVersion = Math.addExact(current.stateVersion(), 1L);
                } catch (ArithmeticException exception) {
                    throw new UniqueItemAuthorityException("Item upgrade/version overflow: " + itemInstanceId, exception);
                }
                new UpgradeState(nextUpgradeLevel);

                long playerStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion,
                        normalizedZone,
                        normalizedEntry,
                        nextStatePayload,
                        (validatedPlayerId, currentPayload, nextPayload) -> {
                            if (!session.playerId().equals(validatedPlayerId)) {
                                throw new UniqueItemAuthorityException(
                                        "Player-state session identity changed during item upgrade"
                                );
                            }
                            stateValidator.verifyUpgrade(
                                    validatedPlayerId,
                                    itemInstanceId,
                                    current.definitionId(),
                                    current.stateVersion(),
                                    nextItemStateVersion,
                                    current.upgradeLevel(),
                                    nextUpgradeLevel,
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                updateUpgradeHead(
                        connection,
                        itemInstanceId,
                        current.stateVersion(),
                        current.upgradeLevel(),
                        nextItemStateVersion,
                        nextUpgradeLevel
                );
                insertUpgradeEvent(
                        connection,
                        operationId,
                        itemInstanceId,
                        current.stateVersion(),
                        nextItemStateVersion,
                        current.upgradeLevel(),
                        nextUpgradeLevel,
                        normalizedReason,
                        session.playerId()
                );
                insertUpgradeProvenance(
                        connection,
                        operationId,
                        itemInstanceId,
                        nextItemStateVersion,
                        playerLocation,
                        normalizedReason,
                        session.playerId()
                );

                ItemUpgradeResult itemUpgrade = new ItemUpgradeResult(
                        itemInstanceId,
                        current.definitionId(),
                        current.upgradeLevel(),
                        nextUpgradeLevel,
                        current.stateVersion(),
                        nextItemStateVersion,
                        playerLocation,
                        normalizedReason
                );
                PlayerItemUpgradeResult result = new PlayerItemUpgradeResult(
                        session.playerId(),
                        playerStateVersion,
                        itemUpgrade
                );
                insertProcessed(
                        connection,
                        operationId,
                        result,
                        sessionId,
                        normalizedBackendId,
                        expectedPlayerStateVersion,
                        expectedItemStateVersion,
                        expectedUpgradeLevel,
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

    private static LiveSession lockLiveSession(
            Connection connection,
            UUID sessionId,
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
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SessionConflictException("Unknown session: " + sessionId);
                }
                UUID playerId = row.getObject("player_id", UUID.class);
                String ownerBackendId = row.getString("owner_backend_id");
                long stateVersion = row.getLong("state_version");
                SessionStatus status = SessionStatus.valueOf(row.getString("status"));
                boolean leaseValid = row.getBoolean("lease_valid");
                if (!backendId.equals(ownerBackendId)
                        || stateVersion != expectedStateVersion
                        || !leaseValid
                        || (status != SessionStatus.ACTIVE && status != SessionStatus.RECOVERING)) {
                    throw new SessionConflictException(
                            "Carried item upgrade does not match authoritative live session"
                    );
                }
                return new LiveSession(playerId);
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
            PlayerItemUpgradeResult result,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            long expectedItemStateVersion,
            int expectedUpgradeLevel,
            String logicalZoneId,
            String entryPoint,
            String payloadSha256,
            String reason
    ) throws SQLException {
        ItemUpgradeResult item = result.itemUpgrade();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'player_id', ?,
                    'player_state_version', ?,
                    'item_instance_id', ?,
                    'definition_id', ?,
                    'from_upgrade_level', ?,
                    'to_upgrade_level', ?,
                    'from_item_state_version', ?,
                    'to_item_state_version', ?,
                    'session_id', ?,
                    'backend_id', ?,
                    'expected_player_state_version', ?,
                    'expected_item_state_version', ?,
                    'expected_upgrade_level', ?,
                    'logical_zone_id', ?,
                    'entry_point', ?,
                    'payload_sha256', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, OPERATION_TYPE);
            statement.setString(3, result.playerId().toString());
            statement.setLong(4, result.playerStateVersion());
            statement.setString(5, item.itemInstanceId().toString());
            statement.setString(6, item.definitionId());
            statement.setInt(7, item.fromUpgradeLevel());
            statement.setInt(8, item.toUpgradeLevel());
            statement.setLong(9, item.fromStateVersion());
            statement.setLong(10, item.toStateVersion());
            statement.setString(11, sessionId.toString());
            statement.setString(12, backendId);
            statement.setLong(13, expectedPlayerStateVersion);
            statement.setLong(14, expectedItemStateVersion);
            statement.setInt(15, expectedUpgradeLevel);
            statement.setString(16, logicalZoneId);
            statement.setString(17, entryPoint);
            statement.setString(18, payloadSha256);
            statement.setString(19, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedPlayerUpgrade> findProcessed(
            Connection connection,
            UUID operationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'player_id' AS player_id,
                       result ->> 'player_state_version' AS player_state_version,
                       result ->> 'item_instance_id' AS item_instance_id,
                       result ->> 'definition_id' AS definition_id,
                       result ->> 'from_upgrade_level' AS from_upgrade_level,
                       result ->> 'to_upgrade_level' AS to_upgrade_level,
                       result ->> 'from_item_state_version' AS from_item_state_version,
                       result ->> 'to_item_state_version' AS to_item_state_version,
                       result ->> 'session_id' AS session_id,
                       result ->> 'backend_id' AS backend_id,
                       result ->> 'expected_player_state_version' AS expected_player_state_version,
                       result ->> 'expected_item_state_version' AS expected_item_state_version,
                       result ->> 'expected_upgrade_level' AS expected_upgrade_level,
                       result ->> 'logical_zone_id' AS logical_zone_id,
                       result ->> 'entry_point' AS entry_point,
                       result ->> 'payload_sha256' AS payload_sha256,
                       result ->> 'reason' AS reason
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
                    UUID playerId = UUID.fromString(requireField(row, "player_id"));
                    long playerStateVersion = Long.parseLong(requireField(row, "player_state_version"));
                    UUID itemId = UUID.fromString(requireField(row, "item_instance_id"));
                    String definitionId = requireField(row, "definition_id");
                    int fromLevel = Integer.parseInt(requireField(row, "from_upgrade_level"));
                    int toLevel = Integer.parseInt(requireField(row, "to_upgrade_level"));
                    long fromItemVersion = Long.parseLong(requireField(row, "from_item_state_version"));
                    long toItemVersion = Long.parseLong(requireField(row, "to_item_state_version"));
                    String reason = requireField(row, "reason");
                    PlayerItemUpgradeResult result = new PlayerItemUpgradeResult(
                            playerId,
                            playerStateVersion,
                            new ItemUpgradeResult(
                                    itemId,
                                    definitionId,
                                    fromLevel,
                                    toLevel,
                                    fromItemVersion,
                                    toItemVersion,
                                    ItemLocation.playerInventory(playerId),
                                    reason
                            )
                    );
                    return Optional.of(new ProcessedPlayerUpgrade(
                            result,
                            UUID.fromString(requireField(row, "session_id")),
                            requireField(row, "backend_id"),
                            Long.parseLong(requireField(row, "expected_player_state_version")),
                            Long.parseLong(requireField(row, "expected_item_state_version")),
                            Integer.parseInt(requireField(row, "expected_upgrade_level")),
                            row.getString("logical_zone_id"),
                            row.getString("entry_point"),
                            requireField(row, "payload_sha256")
                    ));
                } catch (IllegalArgumentException exception) {
                    throw new UniqueItemAuthorityException(
                            "Malformed persisted player-item-upgrade result for operation_id " + operationId,
                            exception
                    );
                }
            }
        }
    }

    private static String requireField(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        if (value == null || value.isBlank()) {
            throw new UniqueItemAuthorityException(
                    "Processed player-item-upgrade result is missing " + column
            );
        }
        return value;
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
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

    private record LiveSession(UUID playerId) { }

    private record Head(
            String definitionId,
            ItemLocation location,
            long stateVersion,
            int upgradeLevel
    ) { }

    private record ProcessedPlayerUpgrade(
            PlayerItemUpgradeResult result,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            long expectedItemStateVersion,
            int expectedUpgradeLevel,
            String logicalZoneId,
            String entryPoint,
            String payloadSha256
    ) {
        void requireSameRequest(
                UUID sessionId,
                String backendId,
                long expectedPlayerStateVersion,
                UUID itemInstanceId,
                long expectedItemStateVersion,
                int expectedUpgradeLevel,
                String logicalZoneId,
                String entryPoint,
                String payloadSha256,
                String reason,
                UUID operationId
        ) {
            ItemUpgradeResult item = result.itemUpgrade();
            if (!this.sessionId.equals(sessionId)
                    || !this.backendId.equals(backendId)
                    || this.expectedPlayerStateVersion != expectedPlayerStateVersion
                    || !item.itemInstanceId().equals(itemInstanceId)
                    || this.expectedItemStateVersion != expectedItemStateVersion
                    || this.expectedUpgradeLevel != expectedUpgradeLevel
                    || !Objects.equals(this.logicalZoneId, logicalZoneId)
                    || !Objects.equals(this.entryPoint, entryPoint)
                    || !this.payloadSha256.equals(payloadSha256)
                    || !item.reason().equals(reason)) {
                throw new UniqueItemAuthorityException(
                        "operation_id was already used for a different player-item-upgrade request: " + operationId
                );
            }
        }
    }
}
