package io.github.kevinrabbe.minecraftserver.common.pve.map;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemStateRemovalValidator;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
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
 * Live-player Map opening authority.
 *
 * <p>The exact serialized Map representation is removed under the session fence in the same PostgreSQL transaction
 * that destroys unique-item custody and creates the persistent Map run. The original MapAuthorityRepository remains
 * useful for authority-only tests/internal flows; Paper-facing opens must use this state-coupled path.</p>
 */
public final class MapPlayerStateOpenRepository {
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final PlayerStateRepository playerStates;
    private final UniqueItemStateRemovalValidator itemRemovalValidator;

    public MapPlayerStateOpenRepository(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            UniqueItemStateRemovalValidator itemRemovalValidator
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.playerStates = new PlayerStateRepository(dataSource);
        this.itemRemovalValidator = Objects.requireNonNull(itemRemovalValidator, "itemRemovalValidator");
    }

    public MapPlayerStateOpenResult openMap(
            UUID operationId,
            UUID mapItemInstanceId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            long expectedItemStateVersion,
            String logicalZoneId,
            String entryPoint,
            byte[] nextPlayerStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(mapItemInstanceId, "mapItemInstanceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(nextPlayerStatePayload, "nextPlayerStatePayload");
        if (expectedPlayerStateVersion < 0 || expectedItemStateVersion < 0) {
            throw new IllegalArgumentException("expected state versions must be >= 0");
        }
        String backend = requireNonBlank(backendId, "backendId");
        String zone = normalizeOptional(logicalZoneId);
        String entry = normalizeOptional(entryPoint);
        String normalizedReason = requireReason(reason);
        String payloadHash = sha256(nextPlayerStatePayload);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<OpenEvidence> processed = findEvidence(connection, operationId);
                if (processed.isPresent()) {
                    OpenEvidence evidence = processed.orElseThrow();
                    evidence.requireSameRequest(
                            mapItemInstanceId,
                            sessionId,
                            backend,
                            expectedPlayerStateVersion,
                            expectedItemStateVersion,
                            zone,
                            entry,
                            payloadHash,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return evidence.result();
                }
                if (legacyRunExists(connection, operationId)) {
                    throw new MapAuthorityException(
                            "Map open operation_id already belongs to a state-uncoupled open: " + operationId
                    );
                }

                UUID playerId = playerIdForSession(connection, sessionId);
                LockedMapItem item = lockMapItem(connection, mapItemInstanceId);
                requireIndividualDefinition(item.definitionId());
                if (!"PLAYER_INVENTORY".equals(item.locationKind()) || !playerId.equals(item.locationId())) {
                    throw new MapAuthorityException("Map item is not owned in the opening player's inventory");
                }
                if (item.stateVersion() != expectedItemStateVersion) {
                    throw new MapAuthorityException(
                            "Stale Map item state_version: expected " + expectedItemStateVersion
                                    + " but authoritative version is " + item.stateVersion()
                    );
                }
                long destroyedItemVersion = incrementVersion(
                        expectedItemStateVersion,
                        "Map item",
                        mapItemInstanceId
                );

                long playerStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        backend,
                        expectedPlayerStateVersion,
                        zone,
                        entry,
                        nextPlayerStatePayload,
                        (lockedPlayerId, currentPayload, nextPayload) -> {
                            if (!lockedPlayerId.equals(playerId)) {
                                throw new MapAuthorityException("session player changed during Map opening");
                            }
                            itemRemovalValidator.verifyRemoval(
                                    lockedPlayerId,
                                    mapItemInstanceId,
                                    expectedItemStateVersion,
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                consumeMapItem(
                        connection,
                        mapItemInstanceId,
                        expectedItemStateVersion,
                        destroyedItemVersion
                );
                insertItemProvenance(
                        connection,
                        mapItemInstanceId,
                        destroyedItemVersion,
                        operationId,
                        playerId,
                        normalizedReason
                );
                insertItemLedger(
                        connection,
                        operationId,
                        playerId,
                        mapItemInstanceId,
                        normalizedReason
                );

                UUID runId = UUID.randomUUID();
                insertRunFromProfile(
                        connection,
                        runId,
                        mapItemInstanceId,
                        operationId,
                        playerId,
                        expectedItemStateVersion,
                        normalizedReason
                );
                insertEvidence(
                        connection,
                        operationId,
                        runId,
                        sessionId,
                        backend,
                        expectedPlayerStateVersion,
                        playerStateVersion,
                        zone,
                        entry,
                        payloadHash
                );

                MapPlayerStateOpenResult result = new MapPlayerStateOpenResult(
                        runId,
                        playerId,
                        playerStateVersion,
                        destroyedItemVersion
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private ItemDefinition requireIndividualDefinition(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new MapAuthorityException("Map definition must be INDIVIDUAL: " + definitionId);
        }
        return definition;
    }

    private static UUID playerIdForSession(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id
                FROM player_sessions
                WHERE network_session_id = ?
                """)) {
            statement.setObject(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown player session: " + sessionId);
                }
                return row.getObject("player_id", UUID.class);
            }
        }
    }

    private static LockedMapItem lockMapItem(Connection connection, UUID itemInstanceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.definition_id,
                       i.location_kind,
                       i.location_id,
                       i.state_version
                FROM item_instances i
                JOIN map_item_profiles p ON p.item_instance_id = i.item_instance_id
                WHERE i.item_instance_id = ?
                FOR UPDATE OF i
                """)) {
            statement.setObject(1, itemInstanceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown Map item: " + itemInstanceId);
                }
                return new LockedMapItem(
                        row.getString("definition_id"),
                        row.getString("location_kind"),
                        row.getObject("location_id", UUID.class),
                        row.getLong("state_version")
                );
            }
        }
    }

    private static void consumeMapItem(
            Connection connection,
            UUID itemInstanceId,
            long expectedVersion,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE item_instances
                SET location_kind = 'DESTROYED',
                    location_id = NULL,
                    state_version = ?,
                    updated_at = NOW()
                WHERE item_instance_id = ?
                  AND state_version = ?
                  AND location_kind = 'PLAYER_INVENTORY'
                """)) {
            statement.setLong(1, nextVersion);
            statement.setObject(2, itemInstanceId);
            statement.setLong(3, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new MapAuthorityException("Map item changed concurrently while opening: " + itemInstanceId);
            }
        }
    }

    private static void insertItemProvenance(
            Connection connection,
            UUID itemInstanceId,
            long nextVersion,
            UUID operationId,
            UUID playerId,
            String reason
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
                ) VALUES (?, ?, ?, 'DESTROYED', 'PLAYER_INVENTORY', ?, 'DESTROYED', NULL, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setLong(2, nextVersion);
            statement.setObject(3, operationId);
            statement.setObject(4, playerId);
            statement.setString(5, reason);
            statement.setObject(6, playerId);
            statement.executeUpdate();
        }
    }

    private static void insertItemLedger(
            Connection connection,
            UUID operationId,
            UUID playerId,
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
                ) VALUES (?, 0, ?, 'ITEM_INSTANCE', ?, 1, 'DEBIT', ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, playerId);
            statement.setString(3, itemInstanceId.toString());
            statement.setString(4, reason);
            statement.executeUpdate();
        }
    }

    private static void insertRunFromProfile(
            Connection connection,
            UUID runId,
            UUID sourceItemId,
            UUID openOperationId,
            UUID openedByPlayerId,
            long expectedItemStateVersion,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO map_runs(
                    run_id,
                    source_map_item_id,
                    status,
                    difficulty,
                    environment_id,
                    enemy_family_id,
                    objective_id,
                    modifier_ids,
                    generation_seed,
                    generation_version,
                    balance_version,
                    world_era_id,
                    state_version,
                    created_at,
                    open_operation_id,
                    opened_by_player_id,
                    source_item_expected_state_version,
                    open_reason
                )
                SELECT ?,
                       p.item_instance_id,
                       'CREATED',
                       p.difficulty,
                       p.environment_id,
                       p.enemy_family_id,
                       p.objective_id,
                       p.modifier_ids,
                       p.generation_seed,
                       p.generation_version,
                       p.balance_version,
                       p.world_era_id,
                       0,
                       NOW(),
                       ?,
                       ?,
                       ?,
                       ?
                FROM map_item_profiles p
                WHERE p.item_instance_id = ?
                """)) {
            statement.setObject(1, runId);
            statement.setObject(2, openOperationId);
            statement.setObject(3, openedByPlayerId);
            statement.setLong(4, expectedItemStateVersion);
            statement.setString(5, reason);
            statement.setObject(6, sourceItemId);
            if (statement.executeUpdate() != 1) {
                throw new MapAuthorityException("Map profile disappeared while opening: " + sourceItemId);
            }
        }
    }

    private static void insertEvidence(
            Connection connection,
            UUID operationId,
            UUID runId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            long playerStateVersion,
            String logicalZoneId,
            String entryPoint,
            String payloadHash
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO map_open_player_state_evidence(
                    open_operation_id,
                    run_id,
                    session_id,
                    backend_id,
                    expected_player_state_version,
                    player_state_version,
                    logical_zone_id,
                    entry_point,
                    payload_sha256
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, runId);
            statement.setObject(3, sessionId);
            statement.setString(4, backendId);
            statement.setLong(5, expectedPlayerStateVersion);
            statement.setLong(6, playerStateVersion);
            statement.setString(7, logicalZoneId);
            statement.setString(8, entryPoint);
            statement.setString(9, payloadHash);
            statement.executeUpdate();
        }
    }

    private static Optional<OpenEvidence> findEvidence(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.run_id,
                       e.session_id,
                       e.backend_id,
                       e.expected_player_state_version,
                       e.player_state_version,
                       e.logical_zone_id,
                       e.entry_point,
                       e.payload_sha256,
                       r.source_map_item_id,
                       r.opened_by_player_id,
                       r.source_item_expected_state_version,
                       r.open_reason
                FROM map_open_player_state_evidence e
                JOIN map_runs r ON r.run_id = e.run_id
                WHERE e.open_operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OpenEvidence(
                        operationId,
                        row.getObject("run_id", UUID.class),
                        row.getObject("source_map_item_id", UUID.class),
                        row.getObject("opened_by_player_id", UUID.class),
                        row.getLong("source_item_expected_state_version"),
                        row.getString("open_reason"),
                        row.getObject("session_id", UUID.class),
                        row.getString("backend_id"),
                        row.getLong("expected_player_state_version"),
                        row.getLong("player_state_version"),
                        row.getString("logical_zone_id"),
                        row.getString("entry_point"),
                        row.getString("payload_sha256")
                ));
            }
        }
    }

    private static boolean legacyRunExists(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM map_runs
                WHERE open_operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
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

    private static long incrementVersion(long current, String authority, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new MapAuthorityException(authority + " state_version overflow: " + id, exception);
        }
    }

    private static String requireReason(String reason) {
        String normalized = requireNonBlank(reason, "reason");
        if (!REASON_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("reason has invalid format: " + normalized);
        }
        return normalized;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedMapItem(
            String definitionId,
            String locationKind,
            UUID locationId,
            long stateVersion
    ) { }

    private record OpenEvidence(
            UUID operationId,
            UUID runId,
            UUID mapItemInstanceId,
            UUID playerId,
            long expectedItemStateVersion,
            String reason,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            long playerStateVersion,
            String logicalZoneId,
            String entryPoint,
            String payloadHash
    ) {
        MapPlayerStateOpenResult result() {
            return new MapPlayerStateOpenResult(
                    runId,
                    playerId,
                    playerStateVersion,
                    incrementVersion(expectedItemStateVersion, "Map item", mapItemInstanceId)
            );
        }

        void requireSameRequest(
                UUID requestedItemId,
                UUID requestedSessionId,
                String requestedBackendId,
                long requestedPlayerStateVersion,
                long requestedItemStateVersion,
                String requestedZone,
                String requestedEntry,
                String requestedPayloadHash,
                String requestedReason,
                UUID requestedOperationId
        ) {
            if (!operationId.equals(requestedOperationId)
                    || !mapItemInstanceId.equals(requestedItemId)
                    || !sessionId.equals(requestedSessionId)
                    || !backendId.equals(requestedBackendId)
                    || expectedPlayerStateVersion != requestedPlayerStateVersion
                    || expectedItemStateVersion != requestedItemStateVersion
                    || !Objects.equals(logicalZoneId, requestedZone)
                    || !Objects.equals(entryPoint, requestedEntry)
                    || !payloadHash.equals(requestedPayloadHash)
                    || !reason.equals(requestedReason)) {
                throw new MapAuthorityException(
                        "Map open operation_id was reused with a different player-state request: " + operationId
                );
            }
        }
    }
}
