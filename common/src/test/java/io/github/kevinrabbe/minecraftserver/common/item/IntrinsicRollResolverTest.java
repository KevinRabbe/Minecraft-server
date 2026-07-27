package io.github.kevinrabbe.minecraftserver.common.item;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntrinsicRollResolverTest {
    @Test
    void resolvesMinimumMidpointAndPerfectQualityUsingIntegerBasisPoints() {
        ItemRollProfile profile = new ItemRollProfile(Map.of(
                "damage", new RollRange(10_000, 12_000),
                "defense", new RollRange(9_000, 11_000)
        ));

        assertEquals(
                Map.of("damage", 10_000, "defense", 9_000),
                IntrinsicRollResolver.resolveMultipliers(profile, Map.of("damage", 0, "defense", 0))
        );
        assertEquals(
                Map.of("damage", 11_000, "defense", 10_000),
                IntrinsicRollResolver.resolveMultipliers(profile, Map.of("damage", 5_000, "defense", 5_000))
        );
        assertEquals(
                Map.of("damage", 12_000, "defense", 11_000),
                IntrinsicRollResolver.resolveMultipliers(profile, Map.of("damage", 10_000, "defense", 10_000))
        );
    }

    @Test
    void emptyProfileRequiresEmptyPersistentRollState() {
        assertEquals(
                Map.of(),
                IntrinsicRollResolver.resolveMultipliers(ItemRollProfile.NONE, Map.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> IntrinsicRollResolver.resolveMultipliers(ItemRollProfile.NONE, Map.of("damage", 1))
        );
    }

    @Test
    void missingUnknownNullAndOutOfRangeQualityFailClosed() {
        ItemRollProfile profile = new ItemRollProfile(Map.of("damage", new RollRange(10_000, 12_000)));

        assertThrows(
                IllegalArgumentException.class,
                () -> IntrinsicRollResolver.resolveMultipliers(profile, Map.of())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> IntrinsicRollResolver.resolveMultipliers(profile, Map.of("damage", 1, "defense", 1))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> IntrinsicRollResolver.resolveMultipliers(profile, singletonNull("damage"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> IntrinsicRollResolver.resolveMultipliers(profile, Map.of("damage", 10_001))
        );
    }

    private static Map<String, Integer> singletonNull(String key) {
        java.util.LinkedHashMap<String, Integer> values = new java.util.LinkedHashMap<>();
        values.put(key, null);
        return values;
    }
}
