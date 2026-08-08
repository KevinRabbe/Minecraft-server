package io.github.kevinrabbe.minecraftserver.paper;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperMapCoinRewardPolicyTest {
    @Test
    void loadsLaunchPolicyAndScalesWithinCap() {
        PaperMapCoinRewardPolicy policy = PaperMapCoinRewardPolicy.loadResource("/content/map-coin-rewards.json");

        assertEquals(100L, policy.amountMinor(1));
        assertEquals(105L, policy.amountMinor(2));
        assertEquals(595L, policy.amountMinor(100));
        assertEquals(1000L, policy.amountMinor(10_000));
    }

    @Test
    void rejectsInvalidPolicyAndDifficulty() {
        assertThrows(IllegalArgumentException.class, () -> new PaperMapCoinRewardPolicy(0, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new PaperMapCoinRewardPolicy(10, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new PaperMapCoinRewardPolicy(10, 1, 9));

        PaperMapCoinRewardPolicy policy = new PaperMapCoinRewardPolicy(10, 1, 20);
        assertThrows(IllegalArgumentException.class, () -> policy.amountMinor(0));
    }

    @Test
    void coinOperationIdentityIsStablePerRunAndPlayer() {
        UUID runId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID firstPlayer = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID secondPlayer = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        UUID first = PaperMapCompletionService.deterministicOperation("coin", runId, firstPlayer);
        assertEquals(first, PaperMapCompletionService.deterministicOperation("coin", runId, firstPlayer));
        assertNotEquals(first, PaperMapCompletionService.deterministicOperation("coin", runId, secondPlayer));
        assertNotEquals(first, PaperMapCompletionService.deterministicOperation("reward", runId));
    }
}
