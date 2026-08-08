package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapDifficulty;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRewardDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunDefinition;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunSnapshot;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapRunStatus;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperMapEncounterContentCatalogTest {
    @Test
    void fixtureEncounterKeepsBoundedScalingAndExactVersionedContent() {
        PaperMapEncounterDefinition encounter = launchCatalog().require(fixtureDefinition(1, 123L));

        assertEquals(1, encounter.generationVersion());
        assertEquals(1, encounter.balanceVersion());
        assertEquals(1, encounter.roles().size());
        assertEquals("spider", encounter.roles().getFirst().roleId());
        assertEquals(EntityType.SPIDER, encounter.roles().getFirst().entityType());
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
    void canonicalBootstrapEncounterResolvesFourDeterministicRelicGuardRoles() {
        PaperMapEncounterDefinition encounter = launchCatalog().require(canonicalDefinition(1, 123L));

        assertEquals("forgotten_bastion_relic_guard_extermination_v1", encounter.encounterId());
        assertEquals(8, encounter.requiredKills(1));
        assertEquals("map.challenge", encounter.rewardMapDefinitionId());
        assertEquals(1.0, encounter.healthMultiplier(1), 0.000001);
        assertEquals(1.0, encounter.damageMultiplier(1), 0.000001);

        Set<String> roleIds = encounter.roles().stream()
                .map(PaperMapEncounterRole::roleId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("sentry", "shieldbearer", "channeler", "juggernaut"), roleIds);
        assertEquals(
                Set.of(EntityType.SKELETON, EntityType.HUSK, EntityType.STRAY, EntityType.RAVAGER),
                encounter.roles().stream().map(PaperMapEncounterRole::entityType).collect(Collectors.toSet())
        );

        SplittableRandom first = new SplittableRandom(987654321L);
        SplittableRandom replay = new SplittableRandom(987654321L);
        for (int index = 0; index < 32; index++) {
            assertEquals(encounter.selectRole(first), encounter.selectRole(replay));
        }
    }

    @Test
    void unsupportedRuntimeSemanticsFailClosedInsteadOfBeingIgnored() {
        PaperMapEncounterContentCatalog content = launchCatalog();

        assertThrows(
                MapAuthorityException.class,
                () -> content.require(fixtureDefinition(1, 123L, "defense", List.of(), 1, 1))
        );
        assertThrows(
                MapAuthorityException.class,
                () -> content.require(fixtureDefinition(1, 123L, "extermination", List.of("volatile"), 1, 1))
        );
        assertThrows(
                MapAuthorityException.class,
                () -> content.require(fixtureDefinition(1, 123L, "extermination", List.of(), 2, 1))
        );
        assertThrows(
                MapAuthorityException.class,
                () -> content.require(fixtureDefinition(1, 123L, "extermination", List.of(), 1, 2))
        );
    }

    @Test
    void rewardResolverProducesOneDeterministicBoundedFixtureSuccessorMapPerParticipant() {
        PaperMapEncounterContentCatalog content = launchCatalog();
        PaperMapRewardResolver resolver = new PaperMapRewardResolver(content);
        UUID participant = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant created = Instant.parse("2026-07-26T00:00:00Z");
        MapRunSnapshot completed = new MapRunSnapshot(
                runId,
                UUID.randomUUID(),
                MapRunStatus.COMPLETED,
                fixtureDefinition(100, 987654321L),
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
        assertEquals(completed.definition().generationVersion(), reward.successorMapDefinition().generationVersion());
        assertEquals(completed.definition().balanceVersion(), reward.successorMapDefinition().balanceVersion());
        assertNotEquals(completed.definition().generationSeed(), reward.successorMapDefinition().generationSeed());
    }

    @Test
    void canonicalEncounterCreatesCanonicalChallengeMapSuccessor() {
        PaperMapRewardResolver resolver = new PaperMapRewardResolver(launchCatalog());
        UUID participant = UUID.randomUUID();
        Instant created = Instant.parse("2026-08-08T00:00:00Z");
        MapRunSnapshot completed = new MapRunSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                MapRunStatus.COMPLETED,
                canonicalDefinition(1, 444L),
                2,
                created,
                created.plusSeconds(1),
                created.plusSeconds(30)
        );

        MapRewardDefinition reward = resolver.resolve(completed, List.of(participant)).getFirst();
        assertEquals("map.challenge", reward.definitionId());
        assertEquals(2, reward.successorMapDefinition().difficulty().value());
        assertEquals("forgotten_bastion", reward.successorMapDefinition().environmentId());
        assertEquals("relic_guard", reward.successorMapDefinition().enemyFamilyId());
        assertEquals("extermination", reward.successorMapDefinition().objectiveId());
    }

    private static PaperMapEncounterContentCatalog launchCatalog() {
        ItemCatalog itemCatalog = new ItemCatalogLoader().loadResource("/content/items.json");
        return PaperMapEncounterContentCatalog.loadResource("/content/map-encounters.json", itemCatalog);
    }

    private static MapRunDefinition fixtureDefinition(int difficulty, long seed) {
        return fixtureDefinition(difficulty, seed, "extermination", List.of(), 1, 1);
    }

    private static MapRunDefinition fixtureDefinition(
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

    private static MapRunDefinition canonicalDefinition(int difficulty, long seed) {
        return new MapRunDefinition(
                new MapDifficulty(difficulty),
                "forgotten_bastion",
                "relic_guard",
                "extermination",
                List.of(),
                seed,
                1,
                1,
                "founding"
        );
    }
}
