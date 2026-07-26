package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyClanWarFrozenRepresentationCatalogTest {
    @Test
    void legacy189V1PinsStarterSwordToIronSword() {
        LegacyClanWarRepresentationCatalog catalog = LegacyClanWarRepresentationCatalog.legacy189V1();

        assertEquals(
                "IRON_SWORD",
                catalog.requireBaselineMaterial(
                        new LegacyLoadoutItem(0, 0, "equipment.starter_sword", "{}", 0)
                )
        );
    }

    @Test
    void frozenV1CatalogStillRejectsUnknownRolledAndUpgradedState() {
        LegacyClanWarRepresentationCatalog catalog = LegacyClanWarRepresentationCatalog.legacy189V1();

        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.requireBaselineMaterial(
                        new LegacyLoadoutItem(0, 0, "equipment.unknown", "{}", 0)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.requireBaselineMaterial(
                        new LegacyLoadoutItem(0, 0, "equipment.starter_sword", "{\"damage\":5000}", 0)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.requireBaselineMaterial(
                        new LegacyLoadoutItem(0, 0, "equipment.starter_sword", "{}", 1)
                )
        );
    }
}
