package io.github.kevinrabbe.minecraftserver.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPveDeathLossListenerTest {
    @Test
    void onlyEnabledOrdinaryNonPvpDeathsApply() {
        assertTrue(PveDeathLossEligibility.shouldApply(true, true, false));
        assertFalse(PveDeathLossEligibility.shouldApply(false, true, false));
        assertFalse(PveDeathLossEligibility.shouldApply(true, false, false));
        assertFalse(PveDeathLossEligibility.shouldApply(true, true, true));
    }
}
