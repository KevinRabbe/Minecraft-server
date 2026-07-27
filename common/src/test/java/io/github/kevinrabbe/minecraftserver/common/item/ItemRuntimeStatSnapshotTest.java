package io.github.kevinrabbe.minecraftserver.common.item;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemRuntimeStatSnapshotTest {
    @Test
    void exposesValidatedRolledPropertyValues() {
        ItemRuntimeStatSnapshot snapshot = snapshot(
                Map.of("damage", 5_000),
                Map.of("damage", 11_000)
        );

        assertEquals(11_000, snapshot.requireIntrinsicMultiplierBasisPoints("damage"));
        assertEquals(new RollQuality(5_000), snapshot.requireRollQuality("damage"));
    }

    @Test
    void rejectsMismatchedQualityAndMultiplierProperties() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(
                Map.of("damage", 5_000),
                Map.of("armor", 11_000)
        ));
    }

    @Test
    void rejectsOutOfRangeQuality() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(
                Map.of("damage", 10_001),
                Map.of("damage", 11_000)
        ));
    }

    @Test
    void rejectsOutOfRangeIntrinsicMultiplier() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(
                Map.of("damage", 5_000),
                Map.of("damage", RollRange.TECHNICAL_MAX_BASIS_POINTS + 1)
        ));
    }

    @Test
    void missingPropertyFailsClosed() {
        ItemRuntimeStatSnapshot snapshot = snapshot(Map.of(), Map.of());

        assertThrows(IllegalArgumentException.class, () -> snapshot.requireIntrinsicMultiplierBasisPoints("damage"));
        assertThrows(IllegalArgumentException.class, () -> snapshot.requireRollQuality("damage"));
    }

    private static ItemRuntimeStatSnapshot snapshot(
            Map<String, Integer> quality,
            Map<String, Integer> multipliers
    ) {
        UUID playerId = UUID.randomUUID();
        return new ItemRuntimeStatSnapshot(
                UUID.randomUUID(),
                "equipment.test",
                ItemLocation.playerInventory(playerId),
                3,
                quality,
                multipliers,
                new UpgradeState(2)
        );
    }
}
