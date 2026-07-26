package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyRankedArenaSettingsTest {
    @Test
    void acceptsSmallConfigDrivenSymmetricArenaAndTemporaryKit() {
        LegacyRankedArenaSettings settings = new LegacyRankedArenaSettings(
                0,
                200,
                0,
                12,
                4,
                6,
                "STONE",
                "BEDROCK",
                "GLASS",
                Arrays.asList(
                        new LegacyRankedArenaSettings.LoadoutEntry("0", "iron_sword", 1),
                        new LegacyRankedArenaSettings.LoadoutEntry("helmet", "iron_helmet", 1)
                )
        );

        assertEquals(12, settings.getHalfSize());
        assertEquals(6, settings.getSpawnOffset());
        assertEquals("IRON_SWORD", settings.getLoadout().get(0).getMaterial());
        assertEquals("helmet", settings.getLoadout().get(1).getSlot());
    }

    @Test
    void rejectsUnsafeGeometryAndAmbiguousLoadoutSlots() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LegacyRankedArenaSettings(
                        0, 255, 0, 12, 4, 6,
                        "STONE", "BEDROCK", "GLASS",
                        Collections.singletonList(new LegacyRankedArenaSettings.LoadoutEntry("0", "IRON_SWORD", 1))
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new LegacyRankedArenaSettings(
                        0, 200, 0, 12, 4, 12,
                        "STONE", "BEDROCK", "GLASS",
                        Collections.singletonList(new LegacyRankedArenaSettings.LoadoutEntry("0", "IRON_SWORD", 1))
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new LegacyRankedArenaSettings(
                        0, 200, 0, 12, 4, 6,
                        "STONE", "BEDROCK", "GLASS",
                        Arrays.asList(
                                new LegacyRankedArenaSettings.LoadoutEntry("0", "IRON_SWORD", 1),
                                new LegacyRankedArenaSettings.LoadoutEntry("00", "BOW", 1)
                        )
                )
        );
    }
}
