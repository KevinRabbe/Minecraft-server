package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalogLoader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BountyContentCatalogLoaderTest {
    private final ItemCatalog items = new ItemCatalogLoader().loadResource("/content/items.json");
    private final SkillProgressionCatalog skills = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");
    private final ResourceSourceCatalog sources = new ResourceSourceCatalogLoader().loadResource(
            "/content/resource-sources.json",
            items,
            skills
    );
    private final BountyContentCatalogLoader loader = new BountyContentCatalogLoader();

    @Test
    void launchResourceLoadsZombieTierAndEligibility() {
        BountyContentCatalog content = loader.loadResource("/content/bounties.json", items, sources);
        BountyTierDefinition tier = content.tiers().require(new BountyFamilyId("zombie"), 1);

        assertEquals(100L, tier.contractFeeMinor());
        assertEquals(10, tier.requiredEligibleKills());
        assertEquals("boss.zombie.t1", tier.bossDefinitionId());
        assertEquals(java.util.List.of("material.zombie_essence"), tier.materialDefinitionIds());
        assertEquals(
                new BountyFamilyId("zombie"),
                content.eligibleFamilyForSource("starter_pve.zombie").orElseThrow()
        );
        assertEquals(Map.of("material.zombie_essence", 2L), content.resolve(java.util.UUID.randomUUID(), tier));
    }

    @Test
    void unknownJsonFieldsFailClosed() {
        String json = """
                {
                  "schema_version": 1,
                  "tiers": [{
                    "family_id": "zombie",
                    "tier": 1,
                    "contract_fee_minor": 100,
                    "required_eligible_kills": 10,
                    "boss_definition_id": "boss.zombie.t1",
                    "eligible_source_ids": ["starter_pve.zombie"],
                    "fixed_rewards": {"material.zombie_essence": 2},
                    "unexpected": true
                  }]
                }
                """;

        assertThrows(BountyException.class, () -> load(json));
    }

    @Test
    void unknownEligibleSourceFailsClosed() {
        String json = """
                {
                  "schema_version": 1,
                  "tiers": [{
                    "family_id": "zombie",
                    "tier": 1,
                    "contract_fee_minor": 100,
                    "required_eligible_kills": 10,
                    "boss_definition_id": "boss.zombie.t1",
                    "eligible_source_ids": ["missing.source"],
                    "fixed_rewards": {"material.zombie_essence": 2}
                  }]
                }
                """;

        assertThrows(BountyException.class, () -> load(json));
    }

    @Test
    void individualizedRewardDefinitionFailsClosed() {
        String json = """
                {
                  "schema_version": 1,
                  "tiers": [{
                    "family_id": "zombie",
                    "tier": 1,
                    "contract_fee_minor": 100,
                    "required_eligible_kills": 10,
                    "boss_definition_id": "boss.zombie.t1",
                    "eligible_source_ids": ["starter_pve.zombie"],
                    "fixed_rewards": {"equipment.starter_sword": 1}
                  }]
                }
                """;

        assertThrows(BountyException.class, () -> load(json));
    }

    private BountyContentCatalog load(String json) {
        return loader.load(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "test-json",
                items,
                sources
        );
    }
}
