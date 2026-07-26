package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyClanWarControlPointGeometryTest {
    @Test
    void usesBoundedThreeDimensionalRadiusAroundConfiguredCenter() {
        LegacyClanWarControlPointGeometry geometry = new LegacyClanWarControlPointGeometry(10.0D, 64.0D, -5.0D, 3.0D);

        assertTrue(geometry.contains(10.0D, 64.0D, -5.0D));
        assertTrue(geometry.contains(13.0D, 64.0D, -5.0D));
        assertFalse(geometry.contains(13.01D, 64.0D, -5.0D));
        assertFalse(geometry.contains(10.0D, 67.01D, -5.0D));
        assertFalse(geometry.contains(Double.NaN, 64.0D, -5.0D));
    }
}
