package io.github.kevinrabbe.minecraftserver.common.artifact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttunementProfileCatalogLoaderTest {
    @Test
    void bundledCatalogContainsOnlySettledArcaneMapping() {
        AttunementProfileCatalog catalog = new AttunementProfileCatalogLoader().loadResource(
                "/content/attunement-profiles.json"
        );

        assertEquals(1, catalog.all().size());
        AttunementProfileDefinition arcane = catalog.require("arcane");
        assertEquals("intelligence", arcane.statKey());
    }

    @Test
    void unknownFieldsAndDuplicateProfilesFailClosed() {
        AttunementProfileCatalogLoader loader = new AttunementProfileCatalogLoader();
        String unknownField = """
                {
                  "schema_version": 1,
                  "profiles": [
                    {"profile_id": "arcane", "stat_key": "intelligence"}
                  ],
                  "unexpected": true
                }
                """;
        assertThrows(
                AttunementException.class,
                () -> loader.load(stream(unknownField), "unknown-field-test")
        );

        String duplicate = """
                {
                  "schema_version": 1,
                  "profiles": [
                    {"profile_id": "arcane", "stat_key": "intelligence"},
                    {"profile_id": "arcane", "stat_key": "intelligence"}
                  ]
                }
                """;
        assertThrows(
                IllegalArgumentException.class,
                () -> loader.load(stream(duplicate), "duplicate-profile-test")
        );
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
