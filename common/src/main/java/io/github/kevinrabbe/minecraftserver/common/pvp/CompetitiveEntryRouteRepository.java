package io.github.kevinrabbe.minecraftserver.common.pvp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read-only exact backend route for players already assigned to an active 1.8.9 competitive execution. */
public final class CompetitiveEntryRouteRepository {
    private final DataSource dataSource;
    private final Duration backendFreshness;
    private final Clock clock;

    public CompetitiveEntryRouteRepository(DataSource dataSource, Duration backendFreshness) {
        this(dataSource, backendFreshness, Clock.systemUTC());
    }

    public CompetitiveEntryRouteRepository(DataSource dataSource, Duration backendFreshness, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.backendFreshness = requirePositive(backendFreshness, "backendFreshness");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Resolves at most one exact live route by Minecraft identity. Any impossible duplicate fails closed rather than
     * choosing an arbitrary backend. A submitted terminal report makes the execution non-routable immediately, even
     * before the trusted control worker completes durable settlement.
     */
    public Optional<CompetitiveEntryRoute> findByMinecraftUuid(UUID minecraftUuid) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Instant now = clock.instant();
        Instant backendFreshAfter = now.minus(backendFreshness);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT e.execution_id,
                            e.activity_kind,
                            e.activity_id,
                            e.backend_id,
                            e.lease_expires_at,
                            p.player_id,
                            p.minecraft_uuid,
                            p.side_key,
                            p.side_id
                     FROM competitive_player_execution_reservations r
                     JOIN competitive_executions e
                       ON e.execution_id = r.execution_id
                     JOIN competitive_execution_participants p
                       ON p.execution_id = e.execution_id
                      AND p.player_id = r.player_id
                     JOIN backends b
                       ON b.backend_id = e.backend_id
                     WHERE p.minecraft_uuid = ?
                       AND e.status = 'ACTIVE'
                       AND e.lease_expires_at > ?
                       AND b.status = 'ONLINE'
                       AND b.last_heartbeat_at >= ?
                       AND NOT EXISTS (
                           SELECT 1
                           FROM competitive_result_reports report
                           WHERE report.execution_id = e.execution_id
                       )
                     ORDER BY e.execution_id ASC
                     LIMIT 2
                     """)) {
            statement.setObject(1, minecraftUuid);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(backendFreshAfter));
            try (ResultSet rows = statement.executeQuery()) {
                List<CompetitiveEntryRoute> routes = new ArrayList<>(2);
                while (rows.next()) routes.add(readRoute(rows));
                if (routes.size() > 1) {
                    throw new CompetitiveExecutionException(
                            "Minecraft UUID has multiple live competitive entry routes: " + minecraftUuid
                    );
                }
                return routes.stream().findFirst();
            }
        }
    }

    /**
     * Reads the complete current routing projection in one query for proxy-side reconciliation. The result contains
     * routing/identity only and fails closed if an impossible duplicate Minecraft identity is observed. Executions with
     * any submitted terminal report are omitted immediately, before trusted settlement closes the execution row.
     */
    public List<CompetitiveEntryRoute> findAllActive() throws SQLException {
        Instant now = clock.instant();
        Instant backendFreshAfter = now.minus(backendFreshness);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT e.execution_id,
                            e.activity_kind,
                            e.activity_id,
                            e.backend_id,
                            e.lease_expires_at,
                            p.player_id,
                            p.minecraft_uuid,
                            p.side_key,
                            p.side_id
                     FROM competitive_player_execution_reservations r
                     JOIN competitive_executions e
                       ON e.execution_id = r.execution_id
                     JOIN competitive_execution_participants p
                       ON p.execution_id = e.execution_id
                      AND p.player_id = r.player_id
                     JOIN backends b
                       ON b.backend_id = e.backend_id
                     WHERE e.status = 'ACTIVE'
                       AND e.lease_expires_at > ?
                       AND b.status = 'ONLINE'
                       AND b.last_heartbeat_at >= ?
                       AND NOT EXISTS (
                           SELECT 1
                           FROM competitive_result_reports report
                           WHERE report.execution_id = e.execution_id
                       )
                     ORDER BY p.minecraft_uuid ASC, e.execution_id ASC
                     """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(backendFreshAfter));
            try (ResultSet rows = statement.executeQuery()) {
                List<CompetitiveEntryRoute> routes = new ArrayList<>();
                Set<UUID> minecraftUuids = new HashSet<>();
                while (rows.next()) {
                    CompetitiveEntryRoute route = readRoute(rows);
                    if (!minecraftUuids.add(route.minecraftUuid())) {
                        throw new CompetitiveExecutionException(
                                "Minecraft UUID has multiple live competitive entry routes: " + route.minecraftUuid()
                        );
                    }
                    routes.add(route);
                }
                return List.copyOf(routes);
            }
        }
    }

    private static CompetitiveEntryRoute readRoute(ResultSet row) throws SQLException {
        return new CompetitiveEntryRoute(
                row.getObject("execution_id", UUID.class),
                CompetitiveActivityKind.valueOf(row.getString("activity_kind")),
                row.getObject("activity_id", UUID.class),
                row.getString("backend_id"),
                row.getObject("player_id", UUID.class),
                row.getObject("minecraft_uuid", UUID.class),
                row.getString("side_key"),
                row.getObject("side_id", UUID.class),
                row.getTimestamp("lease_expires_at").toInstant()
        );
    }

    private static Duration requirePositive(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be > 0");
        }
        return duration;
    }
}
