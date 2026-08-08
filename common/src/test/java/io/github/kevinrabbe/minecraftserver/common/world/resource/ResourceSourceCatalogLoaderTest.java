package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalogLoader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceSourceCatalogLoaderTest {
    @Test
    void bundledStarterSourcesSupportCommodityAndRewardlessCombatCycles() {
        ItemCatalog items = new ItemCatalogLoader().loadResource("/content/items.json");
        SkillProgressionCatalog skills = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");
        ResourceSourceCatalog sources = new ResourceSourceCatalogLoader().loadResource(
                "/content/resource-sources.json",
                items,
                skills
        );

        ResourceSourceDefinition iron = sources.require("starter_mine.iron_ore");
        assertEquals("starter_mine", iron.zoneId());
        assertEquals("v1", iron.templateVersion());
        assertEquals("material.raw_iron", iron.commodityDefinitionId());
        assertEquals(1L, iron.commodityQuantity());
        assertEquals(new SkillId("mining"), iron.skillId());
        assertEquals(10L, iron.requestedExperience());
        assertEquals(Duration.ofSeconds(15), iron.respawnDelay());
        assertTrue(iron.hasCommodityReward());

        ResourceSourceDefinition champion = sources.require("starter_combat.ruinbound_champion");
        assertEquals("city", champion.zoneId());
        assertEquals("city-dev-v1", champion.templateVersion());
        assertNull(champion.commodityDefinitionId());
        assertEquals(0L, champion.commodityQuantity());
        assertNull(champion.skillId());
        assertEquals(0L, champion.requestedExperience());
        assertEquals(Duration.ofMinutes(2), champion.respawnDelay());
        assertFalse(champion.hasCommodityReward());
    }

    @Test
    void unknownItemReferenceFailsAtContentLoadBoundary() {
        ItemCatalog items = new ItemCatalogLoader().loadResource("/content/items.json");
        SkillProgressionCatalog skills = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");
        String invalid = """
                {
                  "schema_version": 2,
                  "sources": [
                    {
                      "definition_id": "starter_mine.bad",
                      "zone_id": "starter_mine",
                      "template_version": "v1",
                      "commodity_definition_id": "material.does_not_exist",
                      "commodity_quantity": 1,
                      "skill_id": "mining",
                      "requested_experience": 1,
                      "respawn_millis": 1000
                    }
                  ]
                }
                """;

        assertThrows(
                RuntimeException.class,
                () -> new ResourceSourceCatalogLoader().load(
                        new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8)),
                        "bad-item-test",
                        items,
                        skills
                )
        );
    }

    @Test
    void rewardlessSourceCannotHidePositiveCommodityQuantity() {
        ItemCatalog items = new ItemCatalogLoader().loadResource("/content/items.json");
        SkillProgressionCatalog skills = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");
        String invalid = """
                {
                  "schema_version": 2,
                  "sources": [
                    {
                      "definition_id": "combat.bad",
                      "zone_id": "city",
                      "template_version": "city-dev-v1",
                      "commodity_definition_id": null,
                      "commodity_quantity": 1,
                      "skill_id": null,
                      "requested_experience": 0,
                      "respawn_millis": 1000
                    }
                  ]
                }
                """;

        assertThrows(
                RuntimeException.class,
                () -> new ResourceSourceCatalogLoader().load(
                        new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8)),
                        "rewardless-shape-test",
                        items,
                        skills
                )
        );
    }
}
