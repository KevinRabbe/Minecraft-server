package io.github.kevinrabbe.minecraftserver.common.pve.map;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded scan for completed Map encounters whose bound slot still requires durable reward/release recovery. */
public final class MapCompletedEncounterRecoveryRepository {
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;

    public MapCompletedEncounterRecoveryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<MapCompletedEncounterCandidate> listRecoverable(int limit) throws SQLException {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT mr.run_id,
                            r.reservation_id,
                            mr.state_version
                     FROM map_encounter_reservations r
                     JOIN map_runs mr ON mr.run_id = r.run_id
                     WHERE r.status = 'BOUND'
                       AND mr.status = 'COMPLETED'
                     ORDER BY mr.finished_at ASC, mr.run_id ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<MapCompletedEncounterCandidate> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new MapCompletedEncounterCandidate(
                            rows.getObject("run_id", UUID.class),
                            rows.getObject("reservation_id", UUID.class),
                            rows.getLong("state_version")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }
}
