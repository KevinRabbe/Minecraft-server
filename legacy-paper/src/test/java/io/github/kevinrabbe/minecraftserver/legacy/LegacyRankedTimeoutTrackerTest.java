package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRankedTimeoutTrackerTest {
    @Test
    void timeoutStartsOnceAndExpiresMonotonically() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        LegacyRankedTimeoutTracker tracker = new LegacyRankedTimeoutTracker(10, now::get);
        UUID executionId = UUID.randomUUID();

        assertFalse(tracker.isExpired(executionId));
        tracker.start(executionId);
        now.addAndGet(9_000_000_000L);
        assertFalse(tracker.isExpired(executionId));

        tracker.start(executionId);
        now.addAndGet(1_000_000_000L);
        assertTrue(tracker.isExpired(executionId), "repeated start must not extend a materialized match timeout");

        tracker.clear(executionId);
        assertFalse(tracker.isExpired(executionId));
    }

    @Test
    void clearAllDropsEphemeralRestartState() {
        AtomicLong now = new AtomicLong();
        LegacyRankedTimeoutTracker tracker = new LegacyRankedTimeoutTracker(1, now::get);
        UUID executionId = UUID.randomUUID();
        tracker.start(executionId);
        now.set(2_000_000_000L);
        assertTrue(tracker.isExpired(executionId));
        tracker.clear();
        assertFalse(tracker.isExpired(executionId));
    }
}
