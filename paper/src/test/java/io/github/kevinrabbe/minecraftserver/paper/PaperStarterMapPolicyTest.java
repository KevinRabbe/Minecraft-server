package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperStarterMapPolicyTest {
    @Test
    void loadsCanonicalRuinboundChampionBootstrapProfile() {
        ItemCatalog items = new ItemCatalog(List.of(new ItemDefinition(
                "map.challenge",
                "MAP",
                "Challenge Map",
                1,
                ItemCategory.PROGRESSION,
                ItemIdentityKind.INDIVIDUAL
        )));

        PaperStarterMapPolicy policy = PaperStarterMapPolicy.loadResource("/content/starter-map.json", items);
        UUID kill = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        var definition = policy.runDefinition("founding", PaperStarterMapIssuanceService.generationSeed(kill));

        assertEquals("starter_combat.ruinbound_champion", policy.sourceDefinitionId());
        assertEquals("city", policy.sourceZoneId());
        assertEquals("map.challenge", policy.mapDefinitionId());
        assertEquals(1, definition.difficulty().value());
        assertEquals("forgotten_bastion", definition.environmentId());
        assertEquals("relic_guard", definition.enemyFamilyId());
        assertEquals("extermination", definition.objectiveId());
        assertTrue(definition.modifierIds().isEmpty());
        assertEquals(1, definition.generationVersion());
        assertEquals(1, definition.balanceVersion());
        assertEquals("founding", definition.worldEraId());
    }

    @Test
    void issueIdentityAndGenerationSeedAreStablePerManagedKill() {
        UUID firstKill = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID secondKill = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        assertEquals(
                PaperStarterMapIssuanceService.issueOperationId(firstKill),
                PaperStarterMapIssuanceService.issueOperationId(firstKill)
        );
        assertNotEquals(
                PaperStarterMapIssuanceService.issueOperationId(firstKill),
                PaperStarterMapIssuanceService.issueOperationId(secondKill)
        );
        assertEquals(
                PaperStarterMapIssuanceService.generationSeed(firstKill),
                PaperStarterMapIssuanceService.generationSeed(firstKill)
        );
    }
}
