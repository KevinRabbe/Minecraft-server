package io.github.kevinrabbe.minecraftserver.common.pve.map;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Releases a bound encounter slot only after the authoritative Map run has reached a terminal state. */
public final class MapEncounterReservationReleaseRepository {
    private final DataSource dataSource;

    public MapEncounterReservationReleaseRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public MapEncounterReservationSnapshot releaseTerminalRun(UUID reservationId, UUID runId) throws SQLException {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(runId, "runId");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                MapEncounterReservationSnapshot current = readReservation(connection, reservationId, true);
                if (current.status() == MapEncounterReservationStatus.RELEASED) {
                    if (!runId.equals(current.runId())) {
                        throw new MapAuthorityException("Released encounter reservation belongs to another run");
                    }
                    connection.commit();
                    return current;
                }
                if (current.status() != MapEncounterReservationStatus.BOUND || !runId.equals(current.runId())) {
                    throw new MapAuthorityException("Encounter reservation is not BOUND to requested run");
                }

                MapRunStatus runStatus = lockRunStatus(connection, runId);
                if (runStatus != MapRunStatus.COMPLETED && runStatus != MapRunStatus.FAILED) {
                    throw new MapAuthorityException(
                            "Encounter reservation cannot be released while Map run is " + runStatus
                    );
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE map_encounter_reservations
                        SET status = 'RELEASED',
                            state_version = state_version + 1,
                            resolved_at = NOW()
                        WHERE reservation_id = ?
                          AND run_id = ?
                          AND status = 'BOUND'
                        """)) {
                    statement.setObject(1, reservationId);
                    statement.setObject(2, runId);
                    if (statement.executeUpdate() != 1) {
                        throw new MapAuthorityException("Encounter reservation changed concurrently during release");
                    }
                }
                MapEncounterReservationSnapshot result = readReservation(connection, reservationId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static MapRunStatus lockRunStatus(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status
                FROM map_runs
                WHERE run_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Unknown Map run: " + runId);
                }
                return MapRunStatus.valueOf(row.getString("status"));
            }
        }
    }

    private static MapEncounterReservationSnapshot readReservation(
            Connection connection,
            UUID reservationId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT open_operation_id,
                       source_map_item_id,
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
                        row.getObject("open_operation_id", UUID.class),
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
                        row.getTimestamp("bound_at") == null ? null : row.getTimestamp("bound_at").toInstant(),
                        row.getTimestamp("resolved_at") == null ? null : row.getTimestamp("resolved_at").toInstant()
                );
            }
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
