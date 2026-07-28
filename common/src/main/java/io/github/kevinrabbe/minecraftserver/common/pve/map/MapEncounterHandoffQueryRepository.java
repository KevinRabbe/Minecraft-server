package io.github.kevinrabbe.minecraftserver.common.pve.map;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only target-backend lookup for the one CREATED Map run assigned to a player on one exact encounter instance. */
public final class MapEncounterHandoffQueryRepository {
    private final DataSource dataSource;

    public MapEncounterHandoffQueryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Optional<MapEncounterHandoffSnapshot> findCreatedForPlayerInstance(
            UUID playerId,
            UUID targetInstanceId
    ) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(targetInstanceId, "targetInstanceId");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT h.run_id,
                            h.reservation_id,
                            h.transfer_id,
                            h.target_backend_id,
                            h.created_at
                     FROM map_encounter_handoffs h
                     JOIN map_runs mr ON mr.run_id = h.run_id
                     JOIN map_encounter_reservations r ON r.reservation_id = h.reservation_id
                     WHERE h.player_id = ?
                       AND h.target_instance_id = ?
                       AND mr.status = 'CREATED'
                       AND r.status = 'BOUND'
                       AND r.run_id = h.run_id
                     ORDER BY h.created_at ASC, h.run_id ASC
                     LIMIT 2
                     """)) {
            statement.setObject(1, playerId);
            statement.setObject(2, targetInstanceId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                MapEncounterHandoffSnapshot result = new MapEncounterHandoffSnapshot(
                        rows.getObject("run_id", UUID.class),
                        rows.getObject("reservation_id", UUID.class),
                        rows.getObject("transfer_id", UUID.class),
                        playerId,
                        targetInstanceId,
                        rows.getString("target_backend_id"),
                        rows.getTimestamp("created_at").toInstant()
                );
                if (rows.next()) {
                    throw new MapAuthorityException(
                            "Multiple CREATED Map handoffs exist for player/instance: "
                                    + playerId + "/" + targetInstanceId
                    );
                }
                return Optional.of(result);
            }
        }
    }
}
