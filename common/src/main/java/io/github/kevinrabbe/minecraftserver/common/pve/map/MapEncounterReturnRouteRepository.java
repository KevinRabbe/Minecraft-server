package io.github.kevinrabbe.minecraftserver.common.pve.map;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only persisted return routing from disposable Map encounter back to the source logical zone. */
public final class MapEncounterReturnRouteRepository {
    private final DataSource dataSource;

    public MapEncounterReturnRouteRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public MapEncounterReturnRoute load(UUID runId) throws SQLException {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT mr.run_id,
                            mr.opened_by_player_id,
                            mr.status,
                            e.logical_zone_id,
                            h.target_backend_id
                     FROM map_runs mr
                     JOIN map_open_player_state_evidence e ON e.run_id = mr.run_id
                     JOIN map_encounter_handoffs h ON h.run_id = mr.run_id
                     WHERE mr.run_id = ?
                     """)) {
            statement.setObject(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("Map run has no persisted encounter return route: " + runId);
                }
                MapEncounterReturnRoute result = read(row);
                if (row.next()) {
                    throw new MapAuthorityException("Map run resolved to multiple encounter return routes: " + runId);
                }
                return result;
            }
        }
    }

    /**
     * Finds the newest terminal handoff for a player on one disposable backend. Used only to evacuate reconnects that
     * arrive after their Map run already failed/completed or after the exact target instance was restarted.
     */
    public Optional<MapEncounterReturnRoute> findLatestTerminalForPlayerBackend(
            UUID playerId,
            String targetBackendId
    ) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        String backend = requireText(targetBackendId, "targetBackendId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT mr.run_id,
                            mr.opened_by_player_id,
                            mr.status,
                            e.logical_zone_id,
                            h.target_backend_id
                     FROM map_encounter_handoffs h
                     JOIN map_runs mr ON mr.run_id = h.run_id
                     JOIN map_open_player_state_evidence e ON e.run_id = mr.run_id
                     WHERE h.player_id = ?
                       AND h.target_backend_id = ?
                       AND mr.status IN ('COMPLETED', 'FAILED')
                     ORDER BY mr.finished_at DESC, h.created_at DESC, mr.run_id DESC
                     LIMIT 1
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, backend);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    private static MapEncounterReturnRoute read(ResultSet row) throws SQLException {
        String sourceZone = row.getString("logical_zone_id");
        if (sourceZone == null || sourceZone.isBlank()) {
            throw new MapAuthorityException(
                    "Map encounter source logical zone is unavailable for run " + row.getObject("run_id", UUID.class)
            );
        }
        return new MapEncounterReturnRoute(
                row.getObject("run_id", UUID.class),
                row.getObject("opened_by_player_id", UUID.class),
                sourceZone,
                row.getString("target_backend_id"),
                MapRunStatus.valueOf(row.getString("status"))
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
