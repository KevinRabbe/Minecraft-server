package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperMapEncounterRouteCatalogTest {
    @Test
    void loadsFixtureAndCanonicalLaunchRoutes() {
        PaperMapEncounterRouteCatalog catalog = PaperMapEncounterRouteCatalog.loadResource(
                "/content/map-encounter-routes.json"
        );

        PaperMapEncounterRoute fixture = catalog.require("forest");
        assertEquals("forest", fixture.environmentId());
        assertEquals("map_encounter", fixture.zoneId());
        assertEquals("v1", fixture.templateVersion());

        PaperMapEncounterRoute canonical = catalog.require("forgotten_bastion");
        assertEquals("forgotten_bastion", canonical.environmentId());
        assertEquals("map_encounter", canonical.zoneId());
        assertEquals("v1", canonical.templateVersion());
        assertTrue(catalog.containsTarget("map_encounter", "v1"));

        assertThrows(MapAuthorityException.class, () -> catalog.require("unknown"));
    }

    @Test
    void rejectsUnknownJsonFields() {
        assertThrows(
                IllegalStateException.class,
                () -> PaperMapEncounterRouteCatalog.loadResource(
                        "/content/map-encounter-routes-invalid.json"
                )
        );
    }
}
