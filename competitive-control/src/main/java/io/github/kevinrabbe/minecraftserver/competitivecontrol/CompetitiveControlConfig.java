package io.github.kevinrabbe.minecraftserver.competitivecontrol;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Small operational configuration for the trusted competitive settlement/recovery worker. */
public record CompetitiveControlConfig(
        Duration backendFreshness,
        Duration maxExecutionLease,
        int batchLimit,
        Duration pollPeriod
) {
    private static final Duration DEFAULT_BACKEND_FRESHNESS = Duration.ofSeconds(30);
    private static final Duration DEFAULT_MAX_EXECUTION_LEASE = Duration.ofMinutes(5);
    private static final int DEFAULT_BATCH_LIMIT = 50;
    private static final Duration DEFAULT_POLL_PERIOD = Duration.ofSeconds(1);

    public CompetitiveControlConfig {
        backendFreshness = requirePositive(backendFreshness, "backendFreshness");
        maxExecutionLease = requirePositive(maxExecutionLease, "maxExecutionLease");
        pollPeriod = requirePositive(pollPeriod, "pollPeriod");
        if (batchLimit < 1 || batchLimit > 500) {
            throw new IllegalArgumentException("batchLimit must be between 1 and 500");
        }
    }

    public static CompetitiveControlConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static CompetitiveControlConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new CompetitiveControlConfig(
                parseDurationSeconds(
                        environment.get("COMPETITIVE_CONTROL_BACKEND_FRESHNESS_SECONDS"),
                        DEFAULT_BACKEND_FRESHNESS,
                        "COMPETITIVE_CONTROL_BACKEND_FRESHNESS_SECONDS",
                        1,
                        3_600
                ),
                parseDurationSeconds(
                        environment.get("COMPETITIVE_CONTROL_MAX_EXECUTION_LEASE_SECONDS"),
                        DEFAULT_MAX_EXECUTION_LEASE,
                        "COMPETITIVE_CONTROL_MAX_EXECUTION_LEASE_SECONDS",
                        1,
                        3_600
                ),
                parseInt(
                        environment.get("COMPETITIVE_CONTROL_BATCH_LIMIT"),
                        DEFAULT_BATCH_LIMIT,
                        "COMPETITIVE_CONTROL_BATCH_LIMIT",
                        1,
                        500
                ),
                parseDurationMillis(
                        environment.get("COMPETITIVE_CONTROL_POLL_PERIOD_MILLIS"),
                        DEFAULT_POLL_PERIOD,
                        "COMPETITIVE_CONTROL_POLL_PERIOD_MILLIS",
                        100,
                        60_000
                )
        );
    }

    private static Duration parseDurationSeconds(
            String raw,
            Duration defaultValue,
            String name,
            long minimum,
            long maximum
    ) {
        if (raw == null || raw.isBlank()) return defaultValue;
        long seconds = parseLong(raw, name, minimum, maximum);
        return Duration.ofSeconds(seconds);
    }

    private static Duration parseDurationMillis(
            String raw,
            Duration defaultValue,
            String name,
            long minimum,
            long maximum
    ) {
        if (raw == null || raw.isBlank()) return defaultValue;
        long millis = parseLong(raw, name, minimum, maximum);
        return Duration.ofMillis(millis);
    }

    private static int parseInt(String raw, int defaultValue, String name, int minimum, int maximum) {
        if (raw == null || raw.isBlank()) return defaultValue;
        long parsed = parseLong(raw, name, minimum, maximum);
        return Math.toIntExact(parsed);
    }

    private static long parseLong(String raw, String name, long minimum, long maximum) {
        final long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a whole number", exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return duration;
    }
}
