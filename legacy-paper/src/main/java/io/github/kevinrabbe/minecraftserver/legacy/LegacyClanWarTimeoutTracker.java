package io.github.kevinrabbe.minecraftserver.legacy;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Ephemeral monotonic timeout tracking that begins only after Clan-War combat is actually opened. */
final class LegacyClanWarTimeoutTracker {
    private final long timeoutNanos;
    private final LongSupplier nanoTime;
    private final Map<UUID, Long> startedAt = new HashMap<UUID, Long>();

    LegacyClanWarTimeoutTracker(int timeoutSeconds) {
        this(timeoutSeconds, System::nanoTime);
    }

    LegacyClanWarTimeoutTracker(int timeoutSeconds, LongSupplier nanoTime) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 1");
        }
        this.timeoutNanos = Math.multiplyExact((long) timeoutSeconds, 1_000_000_000L);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    void start(UUID executionId) {
        Objects.requireNonNull(executionId, "executionId");
        if (!startedAt.containsKey(executionId)) {
            startedAt.put(executionId, nanoTime.getAsLong());
        }
    }

    boolean isExpired(UUID executionId) {
        Objects.requireNonNull(executionId, "executionId");
        Long start = startedAt.get(executionId);
        if (start == null) return false;
        return nanoTime.getAsLong() - start >= timeoutNanos;
    }

    void clear(UUID executionId) {
        startedAt.remove(Objects.requireNonNull(executionId, "executionId"));
    }

    void clear() {
        startedAt.clear();
    }
}
