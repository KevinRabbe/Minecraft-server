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

        Instant observedThrough = observedThrough(windowStart, windowEnd, clock.instant());

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

    /**
     * Computes return-window retention for players whose first-ever network session started in the cohort window.
     * A return requires a separate session start in {@code [returnWindowStart, returnWindowEnd)}; one long initial
     * session cannot count as a return.
     */
    public SessionRetentionSummary retention(
            Instant cohortStart,
            Instant cohortEnd,
            Instant returnWindowStart,
            Instant returnWindowEnd
    ) throws SQLException {
        Objects.requireNonNull(cohortStart, "cohortStart");
        Objects.requireNonNull(cohortEnd, "cohortEnd");
        Objects.requireNonNull(returnWindowStart, "returnWindowStart");
        Objects.requireNonNull(returnWindowEnd, "returnWindowEnd");
        if (!cohortEnd.isAfter(cohortStart)) {
            throw new IllegalArgumentException("cohortEnd must be after cohortStart");
        }
        if (returnWindowStart.isBefore(cohortEnd)) {
            throw new IllegalArgumentException("returnWindowStart must not overlap the cohort window");
        }
        if (!returnWindowEnd.isAfter(returnWindowStart)) {
            throw new IllegalArgumentException("returnWindowEnd must be after returnWindowStart");
        }

        Instant now = clock.instant();
        if (cohortEnd.isAfter(now)) {
            throw new IllegalArgumentException("cohortEnd must not be after current observation time");
        }
        Instant observedReturnThrough = observedThrough(returnWindowStart, returnWindowEnd, now);

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH params AS (
                        SELECT ?::TIMESTAMPTZ AS cohort_start,
                               ?::TIMESTAMPTZ AS cohort_end,
                               ?::TIMESTAMPTZ AS return_start,
                               ?::TIMESTAMPTZ AS observed_return_end
                    ), first_sessions AS (
                        SELECT session.player_id,
                               MIN(session.created_at) AS first_session_at
                        FROM player_sessions session
                        CROSS JOIN params
                        WHERE session.created_at < params.cohort_end
                        GROUP BY session.player_id
                    ), cohort AS (
                        SELECT first_seen.player_id
                        FROM first_sessions first_seen
                        CROSS JOIN params
                        WHERE first_seen.first_session_at >= params.cohort_start
                          AND first_seen.first_session_at < params.cohort_end
                    ), returned AS (
                        SELECT DISTINCT cohort.player_id
                        FROM cohort
                        JOIN player_sessions session
                          ON session.player_id = cohort.player_id
                        CROSS JOIN params
                        WHERE session.created_at >= params.return_start
                          AND session.created_at < params.observed_return_end
                    )
                    SELECT
                        (SELECT COUNT(*) FROM cohort) AS cohort_players,
                        (SELECT COUNT(*) FROM returned) AS returned_players
                    """)) {
                statement.setTimestamp(1, Timestamp.from(cohortStart));
                statement.setTimestamp(2, Timestamp.from(cohortEnd));
                statement.setTimestamp(3, Timestamp.from(returnWindowStart));
                statement.setTimestamp(4, Timestamp.from(observedReturnThrough));
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        throw new SQLException("Session retention query returned no summary row");
                    }
                    return new SessionRetentionSummary(
                            cohortStart,
                            cohortEnd,
                            returnWindowStart,
                            returnWindowEnd,
                            observedReturnThrough,
                            row.getLong("cohort_players"),
                            row.getLong("returned_players")
                    );
                }
            }
        }
    }

    private static Instant observedThrough(Instant windowStart, Instant windowEnd, Instant now) {
        if (now.isBefore(windowStart)) {
            return windowStart;
        }
        if (now.isAfter(windowEnd)) {
            return windowEnd;
        }
        return now;
    }
}
