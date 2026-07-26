package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyClanWarTimeoutTrackerTest {
    @Test
    void timeoutStartsOnceWhenCombatOpensAndDoesNotExtendOnRepeatedTicks() {
        AtomicLong now = new AtomicLong(5_000_000_000L);
        LegacyClanWarTimeoutTracker tracker = new LegacyClanWarTimeoutTracker(15, now::get);
        UUID executionId = UUID.randomUUID();

        assertFalse(tracker.isExpired(executionId));
        tracker.start(executionId);
        now.addAndGet(14_000_000_000L);
        assertFalse(tracker.isExpired(executionId));

        tracker.start(executionId);
        now.addAndGet(1_000_000_000L);
        assertTrue(tracker.isExpired(executionId));

        tracker.clear(executionId);
        assertFalse(tracker.isExpired(executionId));
    }
}
