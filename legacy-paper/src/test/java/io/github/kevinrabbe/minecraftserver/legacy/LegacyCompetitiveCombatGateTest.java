package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyCompetitiveCombatGateTest {
    @Test
    void combatIsClosedUntilExplicitlyEnabledAndCanBeRevoked() {
        LegacyCompetitiveCombatGate gate = new LegacyCompetitiveCombatGate();
        UUID executionA = UUID.randomUUID();
        UUID executionB = UUID.randomUUID();

        assertFalse(gate.isEnabled(executionA));
        assertFalse(gate.isEnabled(executionB));

        gate.enable(executionA);
        assertTrue(gate.isEnabled(executionA));
        assertFalse(gate.isEnabled(executionB));

        gate.disable(executionA);
        assertFalse(gate.isEnabled(executionA));

        gate.enable(executionA);
        gate.enable(executionB);
        gate.clear();
        assertFalse(gate.isEnabled(executionA));
        assertFalse(gate.isEnabled(executionB));
    }

    @Test
    void firstV1ArenaCanReserveCombatForOnlyOneExecutionAtATime() {
        LegacyCompetitiveCombatGate gate = new LegacyCompetitiveCombatGate();
        UUID executionA = UUID.randomUUID();
        UUID executionB = UUID.randomUUID();

        assertTrue(gate.enableExclusive(executionA));
        assertTrue(gate.isEnabled(executionA));
        assertFalse(gate.enableExclusive(executionB));
        assertFalse(gate.isEnabled(executionB));
        assertTrue(gate.enableExclusive(executionA), "idempotent reservation of the same arena must succeed");

        gate.disable(executionA);
        assertTrue(gate.enableExclusive(executionB));
        assertTrue(gate.isEnabled(executionB));
    }
}
