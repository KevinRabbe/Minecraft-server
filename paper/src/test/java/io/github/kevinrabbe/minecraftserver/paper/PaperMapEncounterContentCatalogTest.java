package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperMapEncounterContentCatalogTest {
    @Test
    void launchEncounterHasBoundedScalingAndKillCount() {
        PaperMapEncounterDefinition encounter = launchCatalog().require(definition(1, 123L));

        assertEquals(6, encounter.requiredKills(1));
        assertEquals(7, encounter.requiredKills(11));
        assertEquals(20, encounter.requiredKills(10_000));
        assertEquals(1.0, encounter.healthMultiplier(1), 0.000001);
        assertEquals(4.0, encounter.healthMultiplier(10_000), 0.000001);
        assertEquals(1.0, encounter.damageMultiplier(1), 0.000001);
        assertEquals(3.0, encounter.damageMultiplier(10_000), 0.000001);
        assertEquals(2, encounter.successorDifficulty(1));
        assertEquals(100, encounter.successorDifficulty(100));
        assertEquals(100, encounter.successorDifficulty(1_000));
    }

    @Test
    void unsupportedRuntimeSemanticsFailClosedInsteadOfBeingIgnored() {
        PaperMapEncounterContentCatalog content = launchCatalog();

        assertThrows(
                MapAuthorityException.class,
                () -> content.require(definition(1, 123L, "defense", List.of(), 1, 1))
        );
        assertThrows(
                MapAuthorityException.class,
                () -> content.require(definition(1, 123L, "extermination", List.of("volatile"), 1, 1))
        );
        assertThrows(
                MapAuthorityException.class,
                () -> content.require(definition(1, 123L, "extermination", List.of(), 2, 1))
        );
        assertThrows(
                MapAuthorityException.class,
                () -> content.require(definition(1, 123L, "extermination", List.of(), 1, 2))
        );
    }

    @Test
    void rewardResolverProducesOneDeterministicBoundedSuccessorMapPerParticipant() {
        PaperMapEncounterContentCatalog content = launchCatalog();
        PaperMapRewardResolver resolver = new PaperMapRewardResolver(content);
        UUID participant = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant created = Instant.parse("2026-07-26T00:00:00Z");
        MapRunSnapshot completed = new MapRunSnapshot(
                runId,
                UUID.randomUUID(),
                MapRunStatus.COMPLETED,
                definition(100, 987654321L),
                2,
                created,
                created.plusSeconds(1),
                created.plusSeconds(30)
        );

        List<MapRewardDefinition> first = resolver.resolve(completed, List.of(participant));
        List<MapRewardDefinition> replay = resolver.resolve(completed, List.of(participant));

        assertEquals(first, replay);
        assertEquals(1, first.size());
        MapRewardDefinition reward = first.getFirst();
        assertEquals(participant, reward.playerId());
        assertEquals("map.forest_extermination", reward.definitionId());
        assertEquals(100, reward.successorMapDefinition().difficulty().value());
        assertEquals(completed.definition().environmentId(), reward.successorMapDefinition().environmentId());
        assertEquals(completed.definition().enemyFamilyId(), reward.successorMapDefinition().enemyFamilyId());
        assertEquals(completed.definition().objectiveId(), reward.successorMapDefinition().objectiveId());
        assertNotEquals(completed.definition().generationSeed(), reward.successorMapDefinition().generationSeed());
    }

    private static PaperMapEncounterContentCatalog launchCatalog() {
        ItemCatalog itemCatalog = new ItemCatalogLoader().loadResource("/content/items.json");
        return PaperMapEncounterContentCatalog.loadResource("/content/map-encounters.json", itemCatalog);
    }

    private static MapRunDefinition definition(int difficulty, long seed) {
        return definition(difficulty, seed, "extermination", List.of(), 1, 1);
    }

    private static MapRunDefinition definition(
            int difficulty,
            long seed,
            String objectiveId,
            List<String> modifierIds,
            int generationVersion,
            int balanceVersion
    ) {
        return new MapRunDefinition(
                new MapDifficulty(difficulty),
                "forest",
                "spider",
                objectiveId,
                modifierIds,
                seed,
                generationVersion,
                balanceVersion,
                "founding"
        );
    }
}
