package io.github.kevinrabbe.minecraftserver.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperIntrinsicItemAttributesTest {
    @Test
    void neutralRollPreservesVanillaItemContribution() {
        assertEquals(
                5.0,
                PaperIntrinsicItemAttributes.rolledItemAttackContribution(1.0, 5.0, 10_000),
                1.0e-9
        );
    }

    @Test
    void rolledMultiplierTargetsCompleteVanillaWeaponBaseBeforeLaterBonuses() {
        assertEquals(
                6.2,
                PaperIntrinsicItemAttributes.rolledItemAttackContribution(1.0, 5.0, 12_000),
                1.0e-9
        );
        assertEquals(
                5.6,
                PaperIntrinsicItemAttributes.rolledItemAttackContribution(1.0, 5.0, 11_000),
                1.0e-9
        );
    }

    @Test
    void invalidMathInputsFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PaperIntrinsicItemAttributes.rolledItemAttackContribution(Double.NaN, 5.0, 10_000)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PaperIntrinsicItemAttributes.rolledItemAttackContribution(1.0, 5.0, -1)
        );
    }
}
