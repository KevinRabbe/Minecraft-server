package io.github.kevinrabbe.minecraftserver.common.analytics;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Read-only analytics over authoritative network-session history.
 *
 * <p>No analytics event is written for facts already represented by {@code player_sessions}. This keeps product
 * measurement observational: losing this query surface cannot affect session authority, and analytics cannot become
 * a second source of truth for player ownership or lifecycle.</p>
 */
public final class SessionAnalyticsRepository {
    private final DataSource dataSource;
    private final Clock clock;

    public SessionAnalyticsRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public SessionAnalyticsRepository(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Summarizes activity overlapping {@code [windowStart, windowEnd)} as observed now.
     *
     * <p>For an in-progress window, the result is clipped at {@link SessionActivitySummary#observedThrough()} so an
     * active session never contributes future player-time. New players are active players whose first known network
     * session begins inside the observed portion of this window; returning players first appeared before the window.</p>
     */
    public SessionActivitySummary summarize(Instant windowStart, Instant windowEnd) throws SQLException {
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("windowEnd must be after windowStart");
        }

        Instant now = clock.instant();
        Instant observedThrough;
        if (now.isBefore(windowStart)) {
            observedThrough = windowStart;
        } else if (now.isAfter(windowEnd)) {
            observedThrough = windowEnd;
        } else {
            observedThrough = now;
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH params AS (
                        SELECT ?::TIMESTAMPTZ AS window_start,
                               ?::TIMESTAMPTZ AS observed_end
                    ), overlapping AS (
                        SELECT session.player_id,
                               GREATEST(session.created_at, params.window_start) AS overlap_start,
                               LEAST(COALESCE(session.disconnected_at, params.observed_end), params.observed_end)
                                   AS overlap_end
                        FROM player_sessions session
                        CROSS JOIN params
                        WHERE session.created_at < params.observed_end
                          AND COALESCE(session.disconnected_at, params.observed_end) > params.window_start
                    ), active_players AS (
                        SELECT DISTINCT player_id
                        FROM overlapping
                    ), first_sessions AS (
                        SELECT session.player_id,
                               MIN(session.created_at) AS first_session_at
                        FROM player_sessions session
                        CROSS JOIN params
                        WHERE session.created_at < params.observed_end
                        GROUP BY session.player_id
                    )
                    SELECT
                        (SELECT COUNT(*) FROM active_players) AS unique_players,
                        (
                            SELECT COUNT(*)
                            FROM active_players active
                            JOIN first_sessions first_seen USING (player_id)
                            CROSS JOIN params
                            WHERE first_seen.first_session_at >= params.window_start
                              AND first_seen.first_session_at < params.observed_end
                        ) AS new_players,
                        (
                            SELECT COUNT(*)
                            FROM active_players active
                            JOIN first_sessions first_seen USING (player_id)
                            CROSS JOIN params
                            WHERE first_seen.first_session_at < params.window_start
                        ) AS returning_players,
                        (
                            SELECT COUNT(*)
                            FROM player_sessions session
                            CROSS JOIN params
                            WHERE session.created_at >= params.window_start
                              AND session.created_at < params.observed_end
                        ) AS sessions_started,
                        (
                            SELECT COUNT(*)
                            FROM player_sessions session
                            CROSS JOIN params
                            WHERE session.disconnected_at >= params.window_start
                              AND session.disconnected_at < params.observed_end
                        ) AS sessions_ended,
                        (
                            SELECT COALESCE(
                                FLOOR(EXTRACT(EPOCH FROM COALESCE(
                                    SUM(GREATEST(overlap_end - overlap_start, INTERVAL '0 seconds')),
                                    INTERVAL '0 seconds'
                                ))),
                                0
                            )::BIGINT
                            FROM overlapping
                        ) AS active_player_seconds
                    """)) {
                statement.setTimestamp(1, Timestamp.from(windowStart));
                statement.setTimestamp(2, Timestamp.from(observedThrough));
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        throw new SQLException("Session analytics query returned no summary row");
                    }
                    return new SessionActivitySummary(
                            windowStart,
                            windowEnd,
                            observedThrough,
                            row.getLong("unique_players"),
                            row.getLong("new_players"),
                            row.getLong("returning_players"),
                            row.getLong("sessions_started"),
                            row.getLong("sessions_ended"),
                            row.getLong("active_player_seconds")
                    );
                }
            }
        }
    }
}
