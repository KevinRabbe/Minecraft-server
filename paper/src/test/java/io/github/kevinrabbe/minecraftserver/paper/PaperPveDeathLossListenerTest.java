package io.github.kevinrabbe.minecraftserver.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPveDeathLossListenerTest {
    @Test
    void onlyEnabledOrdinaryNonPvpDeathsApply() {
        assertTrue(PaperPveDeathLossListener.shouldApply(true, true, false));
        assertFalse(PaperPveDeathLossListener.shouldApply(false, true, false));
        assertFalse(PaperPveDeathLossListener.shouldApply(true, false, false));
        assertFalse(PaperPveDeathLossListener.shouldApply(true, true, true));
    }
}
