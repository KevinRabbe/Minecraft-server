package io.github.kevinrabbe.minecraftserver.common.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Read-only product/operations summary derived from authoritative network-session history. */
public record SessionActivitySummary(
        Instant windowStart,
        Instant windowEnd,
        Instant observedThrough,
        long uniquePlayers,
        long newPlayers,
        long returningPlayers,
        long sessionsStarted,
        long sessionsEnded,
        long activePlayerSeconds
) {
    public SessionActivitySummary {
        windowStart = Objects.requireNonNull(windowStart, "windowStart");
        windowEnd = Objects.requireNonNull(windowEnd, "windowEnd");
        observedThrough = Objects.requireNonNull(observedThrough, "observedThrough");
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("windowEnd must be after windowStart");
        }
        if (observedThrough.isBefore(windowStart) || observedThrough.isAfter(windowEnd)) {
            throw new IllegalArgumentException("observedThrough must be inside the requested window");
        }
        if (uniquePlayers < 0 || newPlayers < 0 || returningPlayers < 0
                || sessionsStarted < 0 || sessionsEnded < 0 || activePlayerSeconds < 0) {
            throw new IllegalArgumentException("session analytics values must be nonnegative");
        }
        if (newPlayers + returningPlayers != uniquePlayers) {
            throw new IllegalArgumentException("new + returning players must equal unique active players");
        }
    }

    public Duration activePlayerTime() {
        return Duration.ofSeconds(activePlayerSeconds);
    }
}
