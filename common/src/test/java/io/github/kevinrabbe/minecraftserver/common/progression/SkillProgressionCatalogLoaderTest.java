package io.github.kevinrabbe.minecraftserver.common.progression;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillProgressionCatalogLoaderTest {
    @Test
    void bundledMiningCurveLoadsThroughStrictSchema() {
        SkillProgressionCatalog catalog = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");
        SkillProgressionDefinition mining = catalog.require(new SkillId("mining"));

        assertEquals(0L, mining.experienceForLevel(0));
        assertEquals(250_000L, mining.experienceForLevel(50));
        assertEquals(1_000_000L, mining.experienceForLevel(100));
    }

    @Test
    void unknownFieldsAndMalformedCurvesFailClosed() {
        SkillProgressionCatalogLoader loader = new SkillProgressionCatalogLoader();
        String unknownField = """
                {
                  "schema_version": 1,
                  "skills": [],
                  "unexpected": true
                }
                """;
        assertThrows(
                SkillProgressionException.class,
                () -> loader.load(stream(unknownField), "unknown-field-test")
        );

        String tooShort = """
                {
                  "schema_version": 1,
                  "skills": [
                    {
                      "skill_id": "mining",
                      "cumulative_experience_by_level": [0, 100]
                    }
                  ]
                }
                """;
        assertThrows(
                SkillProgressionException.class,
                () -> loader.load(stream(tooShort), "short-curve-test")
        );
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
