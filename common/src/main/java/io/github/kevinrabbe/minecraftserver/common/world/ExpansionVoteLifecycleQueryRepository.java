package io.github.kevinrabbe.minecraftserver.common.world;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded read projection for due expansion-vote lifecycle transitions. */
public final class ExpansionVoteLifecycleQueryRepository {
    private static final int MAX_LIMIT = 100;

    private final DataSource dataSource;
    private final Clock clock;

    public ExpansionVoteLifecycleQueryRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public ExpansionVoteLifecycleQueryRepository(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** SCHEDULED votes whose configured window is currently open. */
    public List<UUID> listOpenable(int limit) throws SQLException {
        return list(
                """
                SELECT vote_id
                FROM expansion_votes
                WHERE status = 'SCHEDULED'
                  AND opens_at <= ?
                  AND closes_at > ?
                ORDER BY opens_at ASC, vote_id ASC
                LIMIT ?
                """,
                clock.instant(),
                limit
        );
    }

    /** OPEN votes whose configured voting window has ended. */
    public List<UUID> listResolvable(int limit) throws SQLException {
        return list(
                """
                SELECT vote_id
                FROM expansion_votes
                WHERE status = 'OPEN'
                  AND closes_at <= ?
                ORDER BY closes_at ASC, vote_id ASC
                LIMIT ?
                """,
                clock.instant(),
                limit
        );
    }

    private List<UUID> list(String sql, Instant now, int limit) throws SQLException {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp timestamp = Timestamp.from(now);
            statement.setTimestamp(1, timestamp);
            if (sql.contains("closes_at > ?")) {
                statement.setTimestamp(2, timestamp);
                statement.setInt(3, limit);
            } else {
                statement.setInt(2, limit);
            }
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<UUID> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(rows.getObject("vote_id", UUID.class));
                }
                return List.copyOf(result);
            }
        }
    }
}
