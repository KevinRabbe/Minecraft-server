package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyLoadoutItemTest {
    @Test
    void carriesOnlySanitizedExecutionScopedCombatState() {
        LegacyLoadoutItem item = new LegacyLoadoutItem(
                2,
                3,
                "war.snapshot_sword",
                "{\"damage\":1.12}",
                4
        );

        assertEquals(2, item.getParticipantIndex());
        assertEquals(3, item.getLoadoutItemIndex());
        assertEquals("war.snapshot_sword", item.getDefinitionId());
        assertEquals("{\"damage\":1.12}", item.getRollStateJson());
        assertEquals(4, item.getUpgradeLevel());
    }

    @Test
    void rejectsMalformedSnapshotRows() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyLoadoutItem(-1, 0, "item", "{}", 0));
        assertThrows(IllegalArgumentException.class, () -> new LegacyLoadoutItem(0, -1, "item", "{}", 0));
        assertThrows(IllegalArgumentException.class, () -> new LegacyLoadoutItem(0, 0, " ", "{}", 0));
        assertThrows(IllegalArgumentException.class, () -> new LegacyLoadoutItem(0, 0, "item", " ", 0));
        assertThrows(IllegalArgumentException.class, () -> new LegacyLoadoutItem(0, 0, "item", "{}", -1));
    }
}
