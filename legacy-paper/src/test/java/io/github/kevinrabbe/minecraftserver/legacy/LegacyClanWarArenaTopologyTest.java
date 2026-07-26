package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyClanWarArenaTopologyTest {
    @Test
    void acceptsControlPointInsideDisposableArena() {
        LegacyClanWarArenaTopology.requireObjectiveInsideArena(
                arena(),
                new LegacyClanWarControlPointGeometry(128.5D, 201.0D, 0.5D, 3.0D)
        );
    }

    @Test
    void rejectsControlPointOutsideHorizontalOrVerticalInterior() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarArenaTopology.requireObjectiveInsideArena(
                        arena(),
                        new LegacyClanWarControlPointGeometry(200.0D, 201.0D, 0.5D, 3.0D)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarArenaTopology.requireObjectiveInsideArena(
                        arena(),
                        new LegacyClanWarControlPointGeometry(128.5D, 210.0D, 0.5D, 3.0D)
                )
        );
    }

    @Test
    void requiresRankedAndClanWarDisposableRegionsToBeDisjoint() {
        LegacyRankedArenaSettings ranked = ranked(0, 200, 0);
        LegacyClanWarArenaTopology.requireDisjointFromRanked(arena(), ranked);

        LegacyClanWarArenaSettings overlapping = new LegacyClanWarArenaSettings(
                0, 200, 0, 24, 4, 16, 2.0D,
                "STONE", "BEDROCK", "GLASS"
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarArenaTopology.requireDisjointFromRanked(overlapping, ranked)
        );
    }

    private static LegacyClanWarArenaSettings arena() {
        return new LegacyClanWarArenaSettings(
                128, 200, 0, 24, 4, 16, 2.0D,
                "STONE", "BEDROCK", "GLASS"
        );
    }

    private static LegacyRankedArenaSettings ranked(int x, int y, int z) {
        return new LegacyRankedArenaSettings(
                x, y, z, 12, 4, 6, 600,
                "STONE", "BEDROCK", "GLASS",
                Collections.singletonList(
                        new LegacyRankedArenaSettings.LoadoutEntry("0", "IRON_SWORD", 1)
                )
        );
    }
}
