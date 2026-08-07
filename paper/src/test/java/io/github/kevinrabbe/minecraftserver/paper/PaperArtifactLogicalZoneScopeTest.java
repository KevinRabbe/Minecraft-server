package io.github.kevinrabbe.minecraftserver.paper;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperArtifactLogicalZoneScopeTest {
    @Test
    void cityArtifactIsVisibleOnlyOnCityBackend() {
        var placement = placement("city");

        assertTrue(PaperArtifactDiscoveryListener.belongsToLogicalZone(placement, "city"));
        assertTrue(PaperArtifactDiscoveryListener.belongsToLogicalZone(placement, " city "));
        assertFalse(PaperArtifactDiscoveryListener.belongsToLogicalZone(placement, "starter-woods"));
        assertFalse(PaperArtifactDiscoveryListener.belongsToLogicalZone(placement, null));
    }

    @Test
    void unscopedArtifactMatchesOnlyUnscopedBackend() {
        var placement = placement(null);

        assertTrue(PaperArtifactDiscoveryListener.belongsToLogicalZone(placement, null));
        assertTrue(PaperArtifactDiscoveryListener.belongsToLogicalZone(placement, "   "));
        assertFalse(PaperArtifactDiscoveryListener.belongsToLogicalZone(placement, "city"));
    }

    private static PaperArtifactPlacementCatalog.PaperArtifactPlacement placement(String logicalZoneId) {
        return new PaperArtifactPlacementCatalog.PaperArtifactPlacement(
                UUID.fromString("92afacc1-81b1-4e77-8491-27021809bcfc"),
                1L,
                "world",
                logicalZoneId,
                20,
                64,
                8,
                Material.AMETHYST_BLOCK,
                true
        );
    }
}
