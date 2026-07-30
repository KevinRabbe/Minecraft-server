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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals(1, tier.contentVersion());
        assertEquals(100L, tier.contractFeeMinor());
        assertEquals(10, tier.requiredEligibleKills());
        assertEquals("boss.zombie.t1", tier.bossDefinitionId());
        assertEquals(List.of("material.zombie_essence"), tier.materialDefinitionIds());
        assertEquals(
                new BountyFamilyId("zombie"),
                content.eligibleFamilyForSource("starter_pve.zombie").orElseThrow()
        );
        assertEquals(Map.of("material.zombie_essence", 2L), content.resolve(UUID.randomUUID(), tier));
    }

    @Test
    void highestVersionIsCurrentWhileHistoricalVersionKeepsRewardsAndEligibility() {
        BountyFamilyId family = new BountyFamilyId("zombie");
        BountyTierDefinition v1 = new BountyTierDefinition(
                family,
                1,
                1,
                100,
                10,
                "boss.zombie.v1",
                List.of("material.zombie_essence")
        );
        BountyTierDefinition v2 = new BountyTierDefinition(
                family,
                1,
                2,
                150,
                20,
                "boss.zombie.v2",
                List.of("material.zombie_essence")
        );
        BountyContentCatalog content = new BountyContentCatalog(List.of(
                new BountyContentCatalog.ConfiguredTier(v1, List.of("source.v1"), Map.of("material.zombie_essence", 2L)),
                new BountyContentCatalog.ConfiguredTier(v2, List.of("source.v2"), Map.of("material.zombie_essence", 5L))
        ));

        assertEquals(v2, content.tiers().require(family, 1));
        assertEquals(v1, content.tiers().require(family, 1, 1));
        assertEquals(v2, content.tiers().require(family, 1, 2));
        assertEquals(Map.of("material.zombie_essence", 2L), content.resolve(UUID.randomUUID(), v1));
        assertEquals(Map.of("material.zombie_essence", 5L), content.resolve(UUID.randomUUID(), v2));
        assertTrue(content.isEligibleSource(family, 1, 1, "source.v1"));
        assertFalse(content.isEligibleSource(family, 1, 1, "source.v2"));
        assertTrue(content.isEligibleSource(family, 1, 2, "source.v2"));
        assertFalse(content.isEligibleSource(family, 1, 2, "source.v1"));
    }

    @Test
    void unknownJsonFieldsFailClosed() {
        String json = """
                {
                  "schema_version": 1,
                  "tiers": [{
                    "family_id": "zombie",
                    "tier": 1,
                    "content_version": 1,
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
                    "content_version": 1,
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
                    "content_version": 1,
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
