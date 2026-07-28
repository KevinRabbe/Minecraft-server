package io.github.kevinrabbe.minecraftserver.common.world.resource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Binds renewable source cycles to exact runtime entity identities without creating a second reward authority.
 *
 * <p>Successful player kills are settled by preparing a deterministic kill claim and then calling the existing
 * ResourceSourceRepository/ResourceHarvestFulfillmentRepository path with that operation ID. V48's harvest trigger
 * atomically validates the active spawn claim and marks the entity KILLED inside the normal harvest transaction.</p>
 */
public final class ResourceEntitySpawnRepository {
    private static final Duration MAX_PENDING_LEASE = Duration.ofMinutes(5);
    private static final Duration MAX_ACTIVE_LEASE = Duration.ofHours(2);

    private final DataSource dataSource;
    private final ResourceSourceCatalog catalog;

    public ResourceEntitySpawnRepository(DataSource dataSource, ResourceSourceCatalog catalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** One-way marker: once a source is entity-bound, direct harvest without an authorized kill claim fails in DB. */
    public void ensureEntitySource(UUID sourceId) throws SQLException {
        Objects.requireNonNull(sourceId, "sourceId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                LockedSource source = readSource(connection, sourceId, true);
                catalog.require(source.definitionId());
                if (!entityMarkerExists(connection, sourceId)) {
                    if (hasHarvestHistory(connection, sourceId)) {
                        throw new ResourceSourceException(
                                "cannot convert an already-harvested source into an entity-bound source: " + sourceId
                        );
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO resource_entity_sources(source_id)
                            VALUES (?)
                            """)) {
                        statement.setObject(1, sourceId);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /**
     * Reserves the current available source cycle for one pending entity. Empty means cooldown or an unresolved entity
     * already owns that cycle. Expired reservations are retired and advance the cycle before returning empty.
     */
    public Optional<ResourceEntitySpawnSnapshot> reserveSpawn(UUID sourceId, Duration pendingLease) throws SQLException {
        Objects.requireNonNull(sourceId, "sourceId");
        Duration lease = requireDuration(pendingLease, MAX_PENDING_LEASE, "pendingLease");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                LockedSource source = readSource(connection, sourceId, true);
                requireEntityMarker(connection, sourceId);
                requireActiveInstance(source);
                ResourceSourceDefinition definition = catalog.require(source.definitionId());

                Optional<ResourceEntitySpawnSnapshot> current = readSpawnForCycle(
                        connection, sourceId, source.cycleNo(), true
                );
                if (current.isPresent()) {
                    ResourceEntitySpawnSnapshot spawn = current.orElseThrow();
                    if (unresolved(spawn.status()) && !spawn.leaseExpiresAt().isAfter(source.databaseNow())) {
                        expireSpawnAndAdvanceSource(connection, source, spawn, definition.respawnDelay());
                    }
                    connection.commit();
                    return Optional.empty();
                }

                if (source.nextAvailableAt().isAfter(source.databaseNow())) {
                    connection.commit();
                    return Optional.empty();
                }

                UUID spawnId = UUID.randomUUID();
                ResourceEntitySpawnSnapshot result;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO resource_entity_spawns(
                            spawn_id,
                            source_id,
                            source_cycle_no,
                            status,
                            lease_expires_at
                        ) VALUES (?, ?, ?, 'PENDING', NOW() + (? * INTERVAL '1 millisecond'))
                        RETURNING created_at, lease_expires_at
                        """)) {
                    statement.setObject(1, spawnId);
                    statement.setObject(2, sourceId);
                    statement.setLong(3, source.cycleNo());
                    statement.setLong(4, lease.toMillis());
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) {
                            throw new ResourceSourceException("entity spawn reservation returned no row");
                        }
                        result = new ResourceEntitySpawnSnapshot(
                                spawnId,
                                sourceId,
                                source.cycleNo(),
                                ResourceEntitySpawnStatus.PENDING,
                                null,
                                row.getTimestamp("lease_expires_at").toInstant(),
                                null,
                                row.getTimestamp("created_at").toInstant(),
                                null,
                                null
                        );
                    }
                }
                connection.commit();
                return Optional.of(result);
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Confirms the exact Bukkit entity created for a pending source-cycle reservation. */
    public ResourceEntitySpawnSnapshot confirmSpawn(
            UUID spawnId,
            UUID entityUuid,
            Duration activeLifetime
    ) throws SQLException {
        Objects.requireNonNull(spawnId, "spawnId");
        Objects.requireNonNull(entityUuid, "entityUuid");
        Duration lifetime = requireDuration(activeLifetime, MAX_ACTIVE_LEASE, "activeLifetime");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ResourceEntitySpawnSnapshot spawn = readSpawn(connection, spawnId, true)
                        .orElseThrow(() -> new ResourceSourceException("Unknown entity spawn: " + spawnId));
                if (spawn.status() == ResourceEntitySpawnStatus.ACTIVE) {
                    if (!entityUuid.equals(spawn.entityUuid())) {
                        throw new ResourceSourceException("entity spawn is already confirmed to another entity UUID");
                    }
                    connection.commit();
                    return spawn;
                }
                if (spawn.status() != ResourceEntitySpawnStatus.PENDING) {
                    throw new ResourceSourceException("entity spawn is not confirmable from status " + spawn.status());
                }

                LockedSource source = readSource(connection, spawn.sourceId(), true);
                requireEntityMarker(connection, spawn.sourceId());
                requireActiveInstance(source);
                if (source.cycleNo() != spawn.sourceCycleNo()) {
                    throw new ResourceSourceException("pending entity spawn no longer owns the current source cycle");
                }
                if (!spawn.leaseExpiresAt().isAfter(source.databaseNow())) {
                    throw new ResourceSourceException("pending entity spawn lease expired before confirmation");
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE resource_entity_spawns
                        SET status = 'ACTIVE',
                            entity_uuid = ?,
                            confirmed_at = NOW(),
                            lease_expires_at = NOW() + (? * INTERVAL '1 millisecond')
                        WHERE spawn_id = ? AND status = 'PENDING'
                        RETURNING source_id,
                                  source_cycle_no,
                                  entity_uuid,
                                  lease_expires_at,
                                  created_at,
                                  confirmed_at
                        """)) {
                    statement.setObject(1, entityUuid);
                    statement.setLong(2, lifetime.toMillis());
                    statement.setObject(3, spawnId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) {
                            throw new ResourceSourceException("entity spawn changed concurrently during confirmation");
                        }
                        ResourceEntitySpawnSnapshot result = new ResourceEntitySpawnSnapshot(
                                spawnId,
                                row.getObject("source_id", UUID.class),
                                row.getLong("source_cycle_no"),
                                ResourceEntitySpawnStatus.ACTIVE,
                                row.getObject("entity_uuid", UUID.class),
                                row.getTimestamp("lease_expires_at").toInstant(),
                                null,
                                row.getTimestamp("created_at").toInstant(),
                                row.getTimestamp("confirmed_at").toInstant(),
                                null
                        );
                        connection.commit();
                        return result;
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /**
     * Creates exactly one immutable kill claim for a live entity. The returned operation ID must be passed unchanged
     * to ResourceSourceRepository.harvest/ResourceGatheringService.harvestAndFulfill.
     */
    public ResourceEntityKillClaim prepareKillClaim(UUID spawnId, UUID entityUuid) throws SQLException {
        Objects.requireNonNull(spawnId, "spawnId");
        Objects.requireNonNull(entityUuid, "entityUuid");
        UUID operationId = killOperationId(spawnId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ResourceEntityKillClaim> existing = readKillClaim(connection, operationId);
                if (existing.isPresent()) {
                    ResourceEntityKillClaim claim = existing.orElseThrow();
                    if (!claim.spawnId().equals(spawnId) || !claim.entityUuid().equals(entityUuid)) {
                        throw new ResourceSourceException("entity kill operation is already bound differently");
                    }
                    connection.commit();
                    return claim;
                }

                ResourceEntitySpawnSnapshot spawn = readSpawn(connection, spawnId, true)
                        .orElseThrow(() -> new ResourceSourceException("Unknown entity spawn: " + spawnId));
                LockedSource source = readSource(connection, spawn.sourceId(), true);
                requireEntityMarker(connection, spawn.sourceId());
                if (spawn.status() != ResourceEntitySpawnStatus.ACTIVE
                        || !entityUuid.equals(spawn.entityUuid())
                        || source.cycleNo() != spawn.sourceCycleNo()
                        || !spawn.leaseExpiresAt().isAfter(source.databaseNow())) {
                    throw new ResourceSourceException("entity kill does not match the active source-cycle binding");
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO resource_entity_kill_claims(operation_id, spawn_id, entity_uuid)
                        VALUES (?, ?, ?)
                        """)) {
                    statement.setObject(1, operationId);
                    statement.setObject(2, spawnId);
                    statement.setObject(3, entityUuid);
                    statement.executeUpdate();
                }
                ResourceEntityKillClaim result = new ResourceEntityKillClaim(
                        operationId,
                        spawnId,
                        spawn.sourceId(),
                        spawn.sourceCycleNo(),
                        entityUuid
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Failed Paper spawn attempt: consume no reward and immediately make a new source cycle eligible. */
    public ResourceEntitySpawnSnapshot cancelPending(UUID spawnId) throws SQLException {
        Objects.requireNonNull(spawnId, "spawnId");
        return resolveWithoutReward(spawnId, null, ResourceEntitySpawnStatus.CANCELLED, Duration.ZERO, true);
    }

    /** Entity died without an eligible player kill: advance the source cycle and apply its normal respawn delay. */
    public ResourceEntitySpawnSnapshot resolveWithoutReward(UUID spawnId, UUID entityUuid) throws SQLException {
        Objects.requireNonNull(entityUuid, "entityUuid");
        return resolveWithoutReward(spawnId, entityUuid, ResourceEntitySpawnStatus.CANCELLED, null, false);
    }

    /** Expires the current stale PENDING/ACTIVE binding, if any, and advances the source cycle without reward. */
    public Optional<UUID> expireStaleSpawn(UUID sourceId) throws SQLException {
        Objects.requireNonNull(sourceId, "sourceId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                LockedSource source = readSource(connection, sourceId, true);
                requireEntityMarker(connection, sourceId);
                ResourceSourceDefinition definition = catalog.require(source.definitionId());
                Optional<ResourceEntitySpawnSnapshot> current = readSpawnForCycle(
                        connection, sourceId, source.cycleNo(), true
                );
                if (current.isEmpty()) {
                    connection.commit();
                    return Optional.empty();
                }
                ResourceEntitySpawnSnapshot spawn = current.orElseThrow();
                if (!unresolved(spawn.status()) || spawn.leaseExpiresAt().isAfter(source.databaseNow())) {
                    connection.commit();
                    return Optional.empty();
                }

                expireSpawnAndAdvanceSource(connection, source, spawn, definition.respawnDelay());
                connection.commit();
                return Optional.ofNullable(spawn.entityUuid());
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public Optional<ResourceEntitySpawnSnapshot> loadSpawn(UUID spawnId) throws SQLException {
        Objects.requireNonNull(spawnId, "spawnId");
        try (Connection connection = dataSource.getConnection()) {
            return readSpawn(connection, spawnId, false);
        }
    }

    public static UUID killOperationId(UUID spawnId) {
        Objects.requireNonNull(spawnId, "spawnId");
        return UUID.nameUUIDFromBytes(
                ("resource-entity-kill:" + spawnId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private ResourceEntitySpawnSnapshot resolveWithoutReward(
            UUID spawnId,
            UUID expectedEntityUuid,
            ResourceEntitySpawnStatus terminalStatus,
            Duration explicitDelay,
            boolean pendingOnly
    ) throws SQLException {
        Objects.requireNonNull(spawnId, "spawnId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ResourceEntitySpawnSnapshot spawn = readSpawn(connection, spawnId, true)
                        .orElseThrow(() -> new ResourceSourceException("Unknown entity spawn: " + spawnId));
                if (!unresolved(spawn.status())) {
                    connection.commit();
                    return spawn;
                }
                if (pendingOnly && spawn.status() != ResourceEntitySpawnStatus.PENDING) {
                    throw new ResourceSourceException("only a PENDING entity spawn can use cancelPending");
                }
                if (!pendingOnly && spawn.status() != ResourceEntitySpawnStatus.ACTIVE) {
                    throw new ResourceSourceException("only an ACTIVE entity spawn can resolve without reward");
                }
                if (expectedEntityUuid != null && !expectedEntityUuid.equals(spawn.entityUuid())) {
                    throw new ResourceSourceException("entity UUID does not match authorized spawn");
                }

                LockedSource source = readSource(connection, spawn.sourceId(), true);
                requireEntityMarker(connection, spawn.sourceId());
                if (source.cycleNo() != spawn.sourceCycleNo()) {
                    throw new ResourceSourceException("entity spawn no longer matches current source cycle");
                }
                ResourceSourceDefinition definition = catalog.require(source.definitionId());
                Duration delay = explicitDelay == null ? definition.respawnDelay() : explicitDelay;
                advanceSource(connection, source, delay);
                transitionTerminal(connection, spawn.spawnId(), terminalStatus);

                ResourceEntitySpawnSnapshot result = readSpawn(connection, spawn.spawnId(), false)
                        .orElseThrow();
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private void expireSpawnAndAdvanceSource(
            Connection connection,
            LockedSource source,
            ResourceEntitySpawnSnapshot spawn,
            Duration respawnDelay
    ) throws SQLException {
        if (source.cycleNo() != spawn.sourceCycleNo()) {
            throw new ResourceSourceException("expired spawn no longer matches current source cycle");
        }
        advanceSource(connection, source, respawnDelay);
        transitionTerminal(connection, spawn.spawnId(), ResourceEntitySpawnStatus.EXPIRED);
    }

    private static void transitionTerminal(
            Connection connection,
            UUID spawnId,
            ResourceEntitySpawnStatus status
    ) throws SQLException {
        if (status != ResourceEntitySpawnStatus.CANCELLED && status != ResourceEntitySpawnStatus.EXPIRED) {
            throw new IllegalArgumentException("manual terminal status must be CANCELLED or EXPIRED");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE resource_entity_spawns
                SET status = ?, resolved_at = NOW()
                WHERE spawn_id = ? AND status IN ('PENDING', 'ACTIVE')
                """)) {
            statement.setString(1, status.name());
            statement.setObject(2, spawnId);
            if (statement.executeUpdate() != 1) {
                throw new ResourceSourceException("entity spawn changed concurrently during terminal transition");
            }
        }
    }

    private static void advanceSource(
            Connection connection,
            LockedSource source,
            Duration delay
    ) throws SQLException {
        long nextCycle = increment(source.cycleNo(), "source cycle", source.sourceId());
        long nextVersion = increment(source.stateVersion(), "source state_version", source.sourceId());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE resource_sources
                SET cycle_no = ?,
                    next_available_at = NOW() + (? * INTERVAL '1 millisecond'),
                    state_version = ?,
                    updated_at = NOW()
                WHERE source_id = ?
                  AND cycle_no = ?
                  AND state_version = ?
                """)) {
            statement.setLong(1, nextCycle);
            statement.setLong(2, delay.toMillis());
            statement.setLong(3, nextVersion);
            statement.setObject(4, source.sourceId());
            statement.setLong(5, source.cycleNo());
            statement.setLong(6, source.stateVersion());
            if (statement.executeUpdate() != 1) {
                throw new ResourceSourceException("resource source changed concurrently during entity resolution");
            }
        }
    }

    private static LockedSource readSource(Connection connection, UUID sourceId, boolean forUpdate) throws SQLException {
        String sql = """
                SELECT s.definition_id,
                       s.cycle_no,
                       s.next_available_at,
                       s.state_version,
                       z.status AS instance_status,
                       NOW() AS database_now
                FROM resource_sources s
                JOIN zone_instances z ON z.instance_id = s.instance_id
                WHERE s.source_id = ?
                """ + (forUpdate ? " FOR UPDATE OF s, z" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sourceId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ResourceSourceException("Unknown resource source: " + sourceId);
                }
                return new LockedSource(
                        sourceId,
                        row.getString("definition_id"),
                        row.getLong("cycle_no"),
                        row.getTimestamp("next_available_at").toInstant(),
                        row.getLong("state_version"),
                        row.getString("instance_status"),
                        row.getTimestamp("database_now").toInstant()
                );
            }
        }
    }

    private static Optional<ResourceEntitySpawnSnapshot> readSpawnForCycle(
            Connection connection,
            UUID sourceId,
            long sourceCycleNo,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT spawn_id,
                       source_id,
                       source_cycle_no,
                       status,
                       entity_uuid,
                       lease_expires_at,
                       killer_player_id,
                       created_at,
                       confirmed_at,
                       resolved_at
                FROM resource_entity_spawns
                WHERE source_id = ? AND source_cycle_no = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sourceId);
            statement.setLong(2, sourceCycleNo);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(spawnSnapshot(row)) : Optional.empty();
            }
        }
    }

    private static Optional<ResourceEntitySpawnSnapshot> readSpawn(
            Connection connection,
            UUID spawnId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT spawn_id,
                       source_id,
                       source_cycle_no,
                       status,
                       entity_uuid,
                       lease_expires_at,
                       killer_player_id,
                       created_at,
                       confirmed_at,
                       resolved_at
                FROM resource_entity_spawns
                WHERE spawn_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, spawnId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(spawnSnapshot(row)) : Optional.empty();
            }
        }
    }

    private static ResourceEntitySpawnSnapshot spawnSnapshot(ResultSet row) throws SQLException {
        return new ResourceEntitySpawnSnapshot(
                row.getObject("spawn_id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getLong("source_cycle_no"),
                ResourceEntitySpawnStatus.valueOf(row.getString("status")),
                row.getObject("entity_uuid", UUID.class),
                row.getTimestamp("lease_expires_at").toInstant(),
                row.getObject("killer_player_id", UUID.class),
                row.getTimestamp("created_at").toInstant(),
                nullableInstant(row, "confirmed_at"),
                nullableInstant(row, "resolved_at")
        );
    }

    private static Optional<ResourceEntityKillClaim> readKillClaim(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.operation_id,
                       c.spawn_id,
                       c.entity_uuid,
                       s.source_id,
                       s.source_cycle_no
                FROM resource_entity_kill_claims c
                JOIN resource_entity_spawns s ON s.spawn_id = c.spawn_id
                WHERE c.operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ResourceEntityKillClaim(
                        row.getObject("operation_id", UUID.class),
                        row.getObject("spawn_id", UUID.class),
                        row.getObject("source_id", UUID.class),
                        row.getLong("source_cycle_no"),
                        row.getObject("entity_uuid", UUID.class)
                ));
            }
        }
    }

    private static boolean entityMarkerExists(Connection connection, UUID sourceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM resource_entity_sources WHERE source_id = ?")) {
            statement.setObject(1, sourceId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private static void requireEntityMarker(Connection connection, UUID sourceId) throws SQLException {
        if (!entityMarkerExists(connection, sourceId)) {
            throw new ResourceSourceException("resource source is not entity-bound: " + sourceId);
        }
    }

    private static boolean hasHarvestHistory(Connection connection, UUID sourceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM resource_harvests WHERE source_id = ? LIMIT 1")) {
            statement.setObject(1, sourceId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private static void requireActiveInstance(LockedSource source) {
        if (!"ACTIVE".equals(source.instanceStatus())) {
            throw new ResourceSourceException("entity source instance is not ACTIVE: " + source.sourceId());
        }
    }

    private static boolean unresolved(ResourceEntitySpawnStatus status) {
        return status == ResourceEntitySpawnStatus.PENDING || status == ResourceEntitySpawnStatus.ACTIVE;
    }

    private static Duration requireDuration(Duration duration, Duration maximum, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.isZero() || duration.isNegative() || duration.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(field + " must be > 0 and <= " + maximum);
        }
        return duration;
    }

    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        var timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static long increment(long current, String authority, UUID sourceId) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new ResourceSourceException(authority + " overflow for " + sourceId, exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedSource(
            UUID sourceId,
            String definitionId,
            long cycleNo,
            Instant nextAvailableAt,
            long stateVersion,
            String instanceStatus,
            Instant databaseNow
    ) { }
}
