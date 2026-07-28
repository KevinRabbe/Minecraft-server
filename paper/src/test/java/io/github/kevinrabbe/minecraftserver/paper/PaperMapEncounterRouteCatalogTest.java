package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperMapEncounterRouteCatalogTest {
    @Test
    void loadsLaunchForestRoute() {
        PaperMapEncounterRouteCatalog catalog = PaperMapEncounterRouteCatalog.loadResource(
                "/content/map-encounter-routes.json"
        );

        PaperMapEncounterRoute route = catalog.require("forest");
        assertEquals("forest", route.environmentId());
        assertEquals("map_encounter", route.zoneId());
        assertEquals("v1", route.templateVersion());
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
