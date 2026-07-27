package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRollProfile;
import io.github.kevinrabbe.minecraftserver.common.item.ItemSkillRequirement;
import io.github.kevinrabbe.minecraftserver.common.item.ItemUseEligibility;
import io.github.kevinrabbe.minecraftserver.common.item.ItemUseRequirements;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressSnapshot;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillXpAwardResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperItemUseEligibilityCacheTest {
    private static final SkillId COMBAT = new SkillId("combat");
    private static final String UNRESTRICTED = "equipment.cache_unrestricted";
    private static final String COMBAT_FIVE = "equipment.cache_combat_5";

    @Test
    void unrestrictedCatalogNeedsNoProgressionSnapshot() throws Exception {
        MutableSkillLoader loader = new MutableSkillLoader();
        PaperItemUseEligibilityCache cache = new PaperItemUseEligibilityCache(
                new ItemCatalog(List.of(new ItemDefinition(
                        UNRESTRICTED,
                        "WOODEN_SWORD",
                        "Cache Unrestricted Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                ))),
                loader,
                1
        );

        assertFalse(cache.requiresProgressionSnapshot());
        cache.refresh(UUID.randomUUID());
        assertEquals(0, loader.loadCount());
        assertEquals(0, cache.cachedPlayerCount());
    }

    @Test
    void restrictedItemFailsClosedBeforeRefreshWhileUnrestrictedNeedsNoSnapshot() {
        UUID playerId = UUID.randomUUID();
        MutableSkillLoader loader = new MutableSkillLoader();
        PaperItemUseEligibilityCache cache = cache(loader, 2);

        assertTrue(cache.requiresProgressionSnapshot());
        assertTrue(cache.evaluate(playerId, COMBAT_FIVE).isEmpty());
        ItemUseEligibility unrestricted = cache.evaluate(playerId, UNRESTRICTED).orElseThrow();
        assertTrue(unrestricted.allowed());
        assertTrue(unrestricted.currentSkillLevels().isEmpty());
        assertEquals(0, cache.cachedPlayerCount());
    }

    @Test
    void committedAwardAdvancesAttachedSnapshotWithoutAnotherRefresh() throws Exception {
        UUID playerId = UUID.randomUUID();
        MutableSkillLoader loader = new MutableSkillLoader();
        loader.set(playerId, COMBAT, 4, 1);
        PaperItemUseEligibilityCache cache = cache(loader, 2);
        cache.refresh(playerId);

        ItemUseEligibility before = cache.evaluate(playerId, COMBAT_FIVE).orElseThrow();
        assertFalse(before.allowed());
        assertEquals(4, before.currentSkillLevels().get(COMBAT));

        // A stale attached snapshot is conservative: newly-earned permission is delayed until the commit is applied.
        assertFalse(cache.evaluate(playerId, COMBAT_FIVE).orElseThrow().allowed());
        cache.applyCommittedAward(award(playerId, 4, 5, 2));

        ItemUseEligibility after = cache.evaluate(playerId, COMBAT_FIVE).orElseThrow();
        assertTrue(after.allowed());
        assertEquals(5, after.currentSkillLevels().get(COMBAT));
    }

    @Test
    void staleRefreshCannotRollCommittedAwardBackward() throws Exception {
        UUID playerId = UUID.randomUUID();
        MutableSkillLoader loader = new MutableSkillLoader();
        loader.set(playerId, COMBAT, 4, 1);
        PaperItemUseEligibilityCache cache = cache(loader, 2);
        cache.refresh(playerId);
        cache.applyCommittedAward(award(playerId, 4, 5, 2));
        assertTrue(cache.evaluate(playerId, COMBAT_FIVE).orElseThrow().allowed());

        // Simulates a DB projection that began before the level-5 commit and completes afterward.
        loader.set(playerId, COMBAT, 4, 1);
        cache.refresh(playerId);

        ItemUseEligibility afterStaleRefresh = cache.evaluate(playerId, COMBAT_FIVE).orElseThrow();
        assertTrue(afterStaleRefresh.allowed());
        assertEquals(5, afterStaleRefresh.currentSkillLevels().get(COMBAT));
    }

    @Test
    void olderCommittedAwardCannotRollNewerSnapshotBackward() throws Exception {
        UUID playerId = UUID.randomUUID();
        MutableSkillLoader loader = new MutableSkillLoader();
        loader.set(playerId, COMBAT, 5, 2);
        PaperItemUseEligibilityCache cache = cache(loader, 2);
        cache.refresh(playerId);

        cache.applyCommittedAward(award(playerId, 0, 4, 1));

        ItemUseEligibility afterReplay = cache.evaluate(playerId, COMBAT_FIVE).orElseThrow();
        assertTrue(afterReplay.allowed());
        assertEquals(5, afterReplay.currentSkillLevels().get(COMBAT));
    }

    @Test
    void newerNonMonotonicProjectionInvalidatesInsteadOfGrantingFromUntrustedState() throws Exception {
        UUID playerId = UUID.randomUUID();
        MutableSkillLoader loader = new MutableSkillLoader();
        loader.set(playerId, COMBAT, 5, 2);
        PaperItemUseEligibilityCache cache = cache(loader, 2);
        cache.refresh(playerId);

        loader.set(playerId, COMBAT, 4, 3);
        assertThrows(IllegalStateException.class, () -> cache.refresh(playerId));

        assertTrue(cache.evaluate(playerId, COMBAT_FIVE).isEmpty());
        assertEquals(0, cache.cachedPlayerCount());
    }

    @Test
    void invalidationRemovesRestrictedSnapshot() throws Exception {
        UUID playerId = UUID.randomUUID();
        MutableSkillLoader loader = new MutableSkillLoader();
        loader.set(playerId, COMBAT, 5, 1);
        PaperItemUseEligibilityCache cache = cache(loader, 2);
        cache.refresh(playerId);

        assertTrue(cache.evaluate(playerId, COMBAT_FIVE).orElseThrow().allowed());
        cache.invalidate(playerId);

        assertTrue(cache.evaluate(playerId, COMBAT_FIVE).isEmpty());
        assertEquals(0, cache.cachedPlayerCount());
    }

    @Test
    void boundedCacheRejectsAnotherPlayerWithoutEvictingExistingSnapshot() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        MutableSkillLoader loader = new MutableSkillLoader();
        loader.set(first, COMBAT, 5, 1);
        loader.set(second, COMBAT, 0, 0);
        PaperItemUseEligibilityCache cache = cache(loader, 1);
        cache.refresh(first);

        assertThrows(IllegalStateException.class, () -> cache.refresh(second));

        assertEquals(1, cache.cachedPlayerCount());
        assertTrue(cache.evaluate(first, COMBAT_FIVE).orElseThrow().allowed());
        assertTrue(cache.evaluate(second, COMBAT_FIVE).isEmpty());
    }

    private static PaperItemUseEligibilityCache cache(MutableSkillLoader loader, int maxPlayers) {
        return new PaperItemUseEligibilityCache(itemCatalog(), loader, maxPlayers);
    }

    private static ItemCatalog itemCatalog() {
        return new ItemCatalog(List.of(
                new ItemDefinition(
                        UNRESTRICTED,
                        "WOODEN_SWORD",
                        "Cache Unrestricted Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                ),
                new ItemDefinition(
                        COMBAT_FIVE,
                        "IRON_SWORD",
                        "Cache Combat 5 Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL,
                        ItemRollProfile.NONE,
                        new ItemUseRequirements(List.of(new ItemSkillRequirement(COMBAT, 5)))
                )
        ));
    }

    private static SkillXpAwardResult award(UUID playerId, int previousLevel, int newLevel, long stateVersion) {
        long previousExperience = previousLevel * 10L;
        long newExperience = newLevel * 10L;
        long granted = newExperience - previousExperience;
        return new SkillXpAwardResult(
                playerId,
                COMBAT,
                granted,
                granted,
                previousExperience,
                newExperience,
                previousLevel,
                newLevel,
                50,
                stateVersion,
                "test.cache"
        );
    }

    private static final class MutableSkillLoader implements PaperItemUseEligibilityCache.SkillProjectionLoader {
        private final Map<UUID, Map<SkillId, SkillProgressSnapshot>> snapshots = new HashMap<>();
        private int loadCount;

        void set(UUID playerId, SkillId skillId, int level, long stateVersion) {
            snapshots.computeIfAbsent(playerId, ignored -> new HashMap<>()).put(
                    skillId,
                    new SkillProgressSnapshot(
                            playerId,
                            skillId,
                            level * 10L,
                            level,
                            50,
                            stateVersion
                    )
            );
        }

        int loadCount() {
            return loadCount;
        }

        @Override
        public Map<SkillId, SkillProgressSnapshot> load(UUID playerId, List<SkillId> skillIds) {
            loadCount++;
            Map<SkillId, SkillProgressSnapshot> player = snapshots.getOrDefault(playerId, Map.of());
            LinkedHashMap<SkillId, SkillProgressSnapshot> result = new LinkedHashMap<>();
            for (SkillId skillId : skillIds) {
                SkillProgressSnapshot snapshot = player.get(skillId);
                if (snapshot != null) {
                    result.put(skillId, snapshot);
                }
            }
            return Map.copyOf(result);
        }
    }
}
