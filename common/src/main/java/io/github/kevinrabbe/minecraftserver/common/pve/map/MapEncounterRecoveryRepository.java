package io.github.kevinrabbe.minecraftserver.common.pve.map;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded persisted-state scan for CREATED Map runs that can no longer complete a valid encounter handoff. */
public final class MapEncounterRecoveryRepository {
    private static final Duration MAX_GRACE = Duration.ofMinutes(30);
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;

    public MapEncounterRecoveryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<MapEncounterRecoveryCandidate> listRecoverable(
            Duration noHandoffGrace,
            Duration targetStartGrace,
            int limit
    ) throws SQLException {
        Duration noHandoff = requireGrace(noHandoffGrace, "noHandoffGrace");
        Duration targetStart = requireGrace(targetStartGrace, "targetStartGrace");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT r.run_id,
                            r.reservation_id,
                            r.player_id,
                            mr.state_version AS run_state_version,
                            h.transfer_id,
                            tt.network_session_id,
                            tt.source_backend_id,
                            CASE
                                WHEN h.run_id IS NULL THEN 'NO_HANDOFF'
                                WHEN tt.consumed_at IS NULL AND tt.expires_at <= NOW() THEN 'TRANSFER_EXPIRED'
                                WHEN tt.consumed_at IS NOT NULL
                                     AND ps.owner_backend_id = tt.source_backend_id
                                     AND ps.status = 'ACTIVE' THEN 'RETURNED_TO_SOURCE'
                                WHEN tt.consumed_at IS NOT NULL
                                     AND ps.owner_backend_id = h.target_backend_id
                                     AND ps.owner_instance_id = h.target_instance_id
                                     AND h.created_at <= NOW() - (? * INTERVAL '1 millisecond')
                                    THEN 'TARGET_START_TIMEOUT'
                                ELSE NULL
                            END AS recovery_reason,
                            COALESCE(h.created_at, r.bound_at) AS recovery_order
                     FROM map_encounter_reservations r
                     JOIN map_runs mr ON mr.run_id = r.run_id
                     LEFT JOIN map_encounter_handoffs h ON h.run_id = r.run_id
                     LEFT JOIN transfer_tickets tt ON tt.transfer_id = h.transfer_id
                     LEFT JOIN player_sessions ps ON ps.network_session_id = tt.network_session_id
                     WHERE r.status = 'BOUND'
                       AND mr.status = 'CREATED'
                       AND (
                           (h.run_id IS NULL
                                AND r.bound_at <= NOW() - (? * INTERVAL '1 millisecond'))
                           OR
                           (h.run_id IS NOT NULL AND (
                               (tt.consumed_at IS NULL AND tt.expires_at <= NOW())
                               OR
                               (tt.consumed_at IS NOT NULL
                                    AND ps.owner_backend_id = tt.source_backend_id
                                    AND ps.status = 'ACTIVE')
                               OR
                               (tt.consumed_at IS NOT NULL
                                    AND ps.owner_backend_id = h.target_backend_id
                                    AND ps.owner_instance_id = h.target_instance_id
                                    AND h.created_at <= NOW() - (? * INTERVAL '1 millisecond'))
                           ))
                       )
                     ORDER BY recovery_order ASC, r.run_id ASC
                     LIMIT ?
                     """)) {
            statement.setLong(1, targetStart.toMillis());
            statement.setLong(2, noHandoff.toMillis());
            statement.setLong(3, targetStart.toMillis());
            statement.setInt(4, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<MapEncounterRecoveryCandidate> result = new ArrayList<>();
                while (rows.next()) {
                    String rawReason = rows.getString("recovery_reason");
                    if (rawReason == null) {
                        continue;
                    }
                    result.add(new MapEncounterRecoveryCandidate(
                            rows.getObject("run_id", UUID.class),
                            rows.getObject("reservation_id", UUID.class),
                            rows.getObject("player_id", UUID.class),
                            rows.getLong("run_state_version"),
                            rows.getObject("transfer_id", UUID.class),
                            rows.getObject("network_session_id", UUID.class),
                            rows.getString("source_backend_id"),
                            MapEncounterRecoveryReason.valueOf(rawReason)
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static Duration requireGrace(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative() || value.compareTo(MAX_GRACE) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 30 minutes");
        }
        return value;
    }
}
