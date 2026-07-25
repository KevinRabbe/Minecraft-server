package io.github.kevinrabbe.minecraftserver.common.pve.map;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Reserves one healthy disposable encounter instance before a Map item may be consumed. */
public final class MapEncounterReservationRepository {
    private static final Duration MAX_RESERVATION_LEASE = Duration.ofMinutes(5);
    private static final Pattern ZONE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final Duration heartbeatFreshness;

    public MapEncounterReservationRepository(DataSource dataSource, Duration heartbeatFreshness) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.heartbeatFreshness = Objects.requireNonNull(heartbeatFreshness, "heartbeatFreshness");
        if (heartbeatFreshness.isZero() || heartbeatFreshness.isNegative()) {
            throw new IllegalArgumentException("heartbeatFreshness must be positive");
        }
    }

    /** Idempotently reserves one exact healthy instance for this player's still-owned source Map. */
    public MapEncounterReservationSnapshot reserve(
            UUID playerId,
            UUID sourceMapItemId,
            String targetZoneId,
            String targetTemplateVersion,
            Duration lease
    ) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sourceMapItemId, "sourceMapItemId");
        String zone = requireZoneId(targetZoneId);
        String template = requireNonBlank(targetTemplateVersion, "targetTemplateVersion");
        Duration reservationLease = requireLease(lease);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                expireStaleReserved(connection);
                requireOwnedMap(connection, playerId, sourceMapItemId);

                Optional<MapEncounterReservationSnapshot> existing = findActiveForMap(
                        connection,
                        sourceMapItemId,
                        true
                );
                if (existing.isPresent()) {
                    MapEncounterReservationSnapshot reservation = existing.orElseThrow();
                    if (reservation.status() == MapEncounterReservationStatus.BOUND) {
                        throw new MapAuthorityException(
                                "Map item already belongs to a bound encounter reservation: " + sourceMapItemId
                        );
                    }
                    if (!reservation.playerId().equals(playerId)
                            || !reservation.targetZoneId().equals(zone)
                            || !reservation.targetTemplateVersion().equals(template)) {
                        throw new MapAuthorityException(
                                "Map item already has a different active encounter reservation: " + sourceMapItemId
                        );
                    }
                    connection.commit();
                    return reservation;
                }

                TargetInstance target = chooseHealthyTarget(connection, zone, template);
                UUID reservationId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO map_encounter_reservations(
                            reservation_id,
                            source_map_item_id,
                            player_id,
                            target_instance_id,
                            target_backend_id,
                            target_zone_id,
                            target_template_version,
                            status,
                            lease_expires_at,
                            state_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED',
                                  NOW() + (? * INTERVAL '1 millisecond'), 0)
                        """)) {
                    statement.setObject(1, reservationId);
                    statement.setObject(2, sourceMapItemId);
                    statement.setObject(3, playerId);
                    statement.setObject(4, target.instanceId());
                    statement.setString(5, target.backendId());
                    statement.setString(6, zone);
                    statement.setString(7, template);
                    statement.setLong(8, reservationLease.toMillis());
                    statement.executeUpdate();
                }

                MapEncounterReservationSnapshot result = read(connection, reservationId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public MapEncounterReservationSnapshot load(UUID reservationId) throws SQLException {
        Objects.requireNonNull(reservationId, "reservationId");
        try (Connection connection = dataSource.getConnection()) {
            return read(connection, reservationId, false);
        }
    }

    /** Releases only a pre-open reservation. BOUND reservations require run-terminal cleanup instead. */
    public MapEncounterReservationSnapshot releaseReserved(UUID reservationId, UUID playerId) throws SQLException {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MapEncounterReservationSnapshot current = read(connection, reservationId, true);
                if (!current.playerId().equals(playerId)) {
                    throw new MapAuthorityException("Encounter reservation belongs to another player");
                }
                if (current.status() == MapEncounterReservationStatus.RELEASED
                        || current.status() == MapEncounterReservationStatus.EXPIRED) {
                    connection.commit();
                    return current;
                }
                if (current.status() != MapEncounterReservationStatus.RESERVED) {
                    throw new MapAuthorityException("BOUND encounter reservation cannot be released before run cleanup");
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE map_encounter_reservations
                        SET status = 'RELEASED',
                            state_version = state_version + 1,
                            resolved_at = NOW()
                        WHERE reservation_id = ? AND status = 'RESERVED'
                        """)) {
                    statement.setObject(1, reservationId);
                    if (statement.executeUpdate() != 1) {
                        throw new MapAuthorityException("Encounter reservation changed concurrently during release");
                    }
                }
                MapEncounterReservationSnapshot result = read(connection, reservationId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Called only by the state-coupled Map-open transaction after its persistent run row exists. */
    MapEncounterReservationSnapshot bindWithinTransaction(
            Connection connection,
            UUID reservationId,
            UUID playerId,
            UUID sourceMapItemId,
            UUID runId
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sourceMapItemId, "sourceMapItemId");
        Objects.requireNonNull(runId, "runId");

        MapEncounterReservationSnapshot current = read(connection, reservationId, true);
        if (current.status() == MapEncounterReservationStatus.BOUND) {
            if (!playerId.equals(current.playerId())
                    || !sourceMapItemId.equals(current.sourceMapItemId())
                    || !runId.equals(current.runId())) {
                throw new MapAuthorityException("Encounter reservation is already bound differently: " + reservationId);
            }
            return current;
        }
        if (current.status() != MapEncounterReservationStatus.RESERVED) {
            throw new MapAuthorityException("Encounter reservation is not bindable from status " + current.status());
        }
        if (!playerId.equals(current.playerId()) || !sourceMapItemId.equals(current.sourceMapItemId())) {
            throw new MapAuthorityException("Encounter reservation does not match opening Map/player");
        }
        if (!current.leaseExpiresAt().isAfter(databaseNow(connection))) {
            throw new MapAuthorityException("Encounter reservation expired before Map opening");
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE map_encounter_reservations
                SET status = 'BOUND',
                    run_id = ?,
                    state_version = state_version + 1,
                    bound_at = NOW()
                WHERE reservation_id = ?
                  AND status = 'RESERVED'
                  AND lease_expires_at > NOW()
                """)) {
            statement.setObject(1, runId);
            statement.setObject(2, reservationId);
            if (statement.executeUpdate() != 1) {
                throw new MapAuthorityException("Encounter reservation changed or expired while binding");
            }
        }
        return read(connection, reservationId, false);
    }

    private void expireStaleReserved(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE map_encounter_reservations
                SET status = 'EXPIRED',
                    state_version = state_version + 1,
                    resolved_at = NOW()
                WHERE status = 'RESERVED'
                  AND lease_expires_at <= NOW()
                """)) {
            statement.executeUpdate();
        }
    }

    private static void requireOwnedMap(Connection connection, UUID playerId, UUID sourceMapItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.location_kind, i.location_id
                FROM item_instances i
                JOIN map_item_profiles p ON p.item_instance_id = i.item_instance_id
                WHERE i.item_instance_id = ?
                FOR UPDATE OF i
                """)) {
            statement.setObject(1, sourceMapItemId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown Map item: " + sourceMapItemId);
                }
                if (!"PLAYER_INVENTORY".equals(row.getString("location_kind"))
                        || !playerId.equals(row.getObject("location_id", UUID.class))) {
                    throw new MapAuthorityException("Map item is not owned in the reserving player's inventory");
                }
            }
        }
    }

    private Optional<MapEncounterReservationSnapshot> findActiveForMap(
            Connection connection,
            UUID sourceMapItemId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT reservation_id
                FROM map_encounter_reservations
                WHERE source_map_item_id = ?
                  AND status IN ('RESERVED', 'BOUND')
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sourceMapItemId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                UUID reservationId = row.getObject("reservation_id", UUID.class);
                return Optional.of(read(connection, reservationId, false));
            }
        }
    }

    private TargetInstance chooseHealthyTarget(Connection connection, String zone, String template) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT zi.instance_id, zi.backend_id
                FROM zone_instances zi
                JOIN backends b ON b.backend_id = zi.backend_id
                WHERE zi.zone_id = ?
                  AND zi.template_version = ?
                  AND zi.status = 'ACTIVE'
                  AND b.status = 'ONLINE'
                  AND zi.player_count < zi.hard_capacity
                  AND zi.last_heartbeat_at >= NOW() - (? * INTERVAL '1 millisecond')
                  AND b.last_heartbeat_at >= NOW() - (? * INTERVAL '1 millisecond')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM map_encounter_reservations r
                      WHERE r.target_instance_id = zi.instance_id
                        AND r.status IN ('RESERVED', 'BOUND')
                  )
                ORDER BY zi.player_count ASC, zi.started_at ASC, zi.instance_id ASC
                FOR UPDATE OF zi SKIP LOCKED
                LIMIT 1
                """)) {
            statement.setString(1, zone);
            statement.setString(2, template);
            statement.setLong(3, heartbeatFreshness.toMillis());
            statement.setLong(4, heartbeatFreshness.toMillis());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException(
                            "No healthy free Map encounter instance for " + zone + "/" + template
                    );
                }
                return new TargetInstance(
                        row.getObject("instance_id", UUID.class),
                        row.getString("backend_id")
                );
            }
        }
    }

    private static MapEncounterReservationSnapshot read(
            Connection connection,
            UUID reservationId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT source_map_item_id,
                       player_id,
                       target_instance_id,
                       target_backend_id,
                       target_zone_id,
                       target_template_version,
                       status,
                       run_id,
                       lease_expires_at,
                       state_version,
                       created_at,
                       bound_at,
                       resolved_at
                FROM map_encounter_reservations
                WHERE reservation_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, reservationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown Map encounter reservation: " + reservationId);
                }
                return new MapEncounterReservationSnapshot(
                        reservationId,
                        row.getObject("source_map_item_id", UUID.class),
                        row.getObject("player_id", UUID.class),
                        row.getObject("target_instance_id", UUID.class),
                        row.getString("target_backend_id"),
                        row.getString("target_zone_id"),
                        row.getString("target_template_version"),
                        MapEncounterReservationStatus.valueOf(row.getString("status")),
                        row.getObject("run_id", UUID.class),
                        row.getTimestamp("lease_expires_at").toInstant(),
                        row.getLong("state_version"),
                        row.getTimestamp("created_at").toInstant(),
                        toInstant(row.getTimestamp("bound_at")),
                        toInstant(row.getTimestamp("resolved_at"))
                );
            }
        }
    }

    private static Instant databaseNow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT NOW() AS database_now");
             ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getTimestamp("database_now").toInstant();
        }
    }

    private static Duration requireLease(Duration lease) {
        Objects.requireNonNull(lease, "lease");
        if (lease.isZero() || lease.isNegative() || lease.compareTo(MAX_RESERVATION_LEASE) > 0) {
            throw new IllegalArgumentException("reservation lease must be > 0 and <= 5 minutes");
        }
        return lease;
    }

    private static String requireZoneId(String value) {
        String normalized = requireNonBlank(value, "targetZoneId");
        if (!ZONE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("targetZoneId has invalid format: " + normalized);
        }
        return normalized;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record TargetInstance(UUID instanceId, String backendId) { }
}
