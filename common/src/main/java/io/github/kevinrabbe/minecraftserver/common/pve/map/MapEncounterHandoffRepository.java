package io.github.kevinrabbe.minecraftserver.common.pve.map;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Exactly-once append-only association between one Map run, bound reservation, and pinned transfer. */
public final class MapEncounterHandoffRepository {
    private final DataSource dataSource;

    public MapEncounterHandoffRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public MapEncounterHandoffSnapshot record(
            UUID runId,
            UUID reservationId,
            UUID transferId,
            UUID playerId,
            UUID targetInstanceId,
            String targetBackendId
    ) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        String backend = requireText(targetBackendId, "targetBackendId");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, runId);
                Optional<MapEncounterHandoffSnapshot> existing = findByRun(connection, runId);
                if (existing.isPresent()) {
                    MapEncounterHandoffSnapshot value = existing.orElseThrow();
                    requireSame(value, reservationId, transferId, playerId, targetInstanceId, backend);
                    connection.commit();
                    return value;
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO map_encounter_handoffs(
                            run_id,
                            reservation_id,
                            transfer_id,
                            player_id,
                            target_instance_id,
                            target_backend_id
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, runId);
                    statement.setObject(2, reservationId);
                    statement.setObject(3, transferId);
                    statement.setObject(4, playerId);
                    statement.setObject(5, targetInstanceId);
                    statement.setString(6, backend);
                    statement.executeUpdate();
                }

                MapEncounterHandoffSnapshot result = findByRun(connection, runId).orElseThrow();
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public Optional<MapEncounterHandoffSnapshot> findByRun(UUID runId) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = dataSource.getConnection()) {
            return findByRun(connection, runId);
        }
    }

    public Optional<MapEncounterHandoffSnapshot> findByTransfer(UUID transferId) throws SQLException {
        Objects.requireNonNull(transferId, "transferId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT run_id,
                            reservation_id,
                            player_id,
                            target_instance_id,
                            target_backend_id,
                            created_at
                     FROM map_encounter_handoffs
                     WHERE transfer_id = ?
                     """)) {
            statement.setObject(1, transferId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new MapEncounterHandoffSnapshot(
                        row.getObject("run_id", UUID.class),
                        row.getObject("reservation_id", UUID.class),
                        transferId,
                        row.getObject("player_id", UUID.class),
                        row.getObject("target_instance_id", UUID.class),
                        row.getString("target_backend_id"),
                        row.getTimestamp("created_at").toInstant()
                ));
            }
        }
    }

    private static Optional<MapEncounterHandoffSnapshot> findByRun(Connection connection, UUID runId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT reservation_id,
                       transfer_id,
                       player_id,
                       target_instance_id,
                       target_backend_id,
                       created_at
                FROM map_encounter_handoffs
                WHERE run_id = ?
                """)) {
            statement.setObject(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new MapEncounterHandoffSnapshot(
                        runId,
                        row.getObject("reservation_id", UUID.class),
                        row.getObject("transfer_id", UUID.class),
                        row.getObject("player_id", UUID.class),
                        row.getObject("target_instance_id", UUID.class),
                        row.getString("target_backend_id"),
                        row.getTimestamp("created_at").toInstant()
                ));
            }
        }
    }

    private static void requireSame(
            MapEncounterHandoffSnapshot existing,
            UUID reservationId,
            UUID transferId,
            UUID playerId,
            UUID targetInstanceId,
            String targetBackendId
    ) {
        if (!existing.reservationId().equals(reservationId)
                || !existing.transferId().equals(transferId)
                || !existing.playerId().equals(playerId)
                || !existing.targetInstanceId().equals(targetInstanceId)
                || !existing.targetBackendId().equals(targetBackendId)) {
            throw new MapAuthorityException(
                    "Map run already has a different encounter handoff: " + existing.runId()
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
