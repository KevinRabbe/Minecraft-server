package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRollProfile;
import io.github.kevinrabbe.minecraftserver.common.item.ItemSkillRequirement;
import io.github.kevinrabbe.minecraftserver.common.item.ItemUseEligibility;
import io.github.kevinrabbe.minecraftserver.common.item.ItemUseRequirements;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionQueryRepository;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionRepository;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillXpAwardResult;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PaperItemUseEligibilityCacheIntegrationTest {
    private static final SkillId COMBAT = new SkillId("combat");
    private static final String UNRESTRICTED = "equipment.cache_unrestricted";
    private static final String COMBAT_FIVE = "equipment.cache_combat_5";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private SkillProgressionRepository progression;
    private SkillProgressionQueryRepository progressionQuery;
    private ItemCatalog itemCatalog;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                4
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        SkillProgressionCatalog skillCatalog = new SkillProgressionCatalog(List.of(skill(COMBAT)));
        progression = new SkillProgressionRepository(dataSource, skillCatalog);
        progressionQuery = new SkillProgressionQueryRepository(dataSource, skillCatalog);
        itemCatalog = new ItemCatalog(List.of(
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

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        skill_xp_awards,
                        player_skills,
                        processed_operations,
                        player_state,
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
            statement.execute("""
                    UPDATE progression_state
                    SET active_skill_cap = 50,
                        state_version = 0,
                        source_operation_id = NULL,
                        changed_at = NOW()
                    WHERE singleton = TRUE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void restrictedItemFailsClosedBeforeRefreshWhileUnrestrictedNeedsNoSnapshot() throws Exception {
        UUID playerId = player("CacheCold");
        PaperItemUseEligibilityCache cache = cache(2);

        assertTrue(cache.evaluate(playerId, COMBAT_FIVE).isEmpty());
        ItemUseEligibility unrestricted = cache.evaluate(playerId, UNRESTRICTED).orElseThrow();
        assertTrue(unrestricted.allowed());
        assertTrue(unrestricted.currentSkillLevels().isEmpty());
        assertEquals(0, cache.cachedPlayerCount());
    }

    @Test
    void committedAwardAdvancesAttachedSnapshotWithoutAnotherRefresh() throws Exception {
        UUID playerId = player("CacheAdvance");
        progression.awardExperience(UUID.randomUUID(), playerId, COMBAT, 40, "test.cache");
        PaperItemUseEligibilityCache cache = cache(2);
        cache.refresh(playerId);

        ItemUseEligibility before = cache.evaluate(playerId, COMBAT_FIVE).orElseThrow();
        assertFalse(before.allowed());
        assertEquals(4, before.currentSkillLevels().get(COMBAT));

        SkillXpAwardResult committed = progression.awardExperience(
                UUID.randomUUID(), playerId, COMBAT, 10, "test.cache"
        );

        // A stale attached snapshot is conservative: newly-earned permission is delayed until the commit is applied.
        assertFalse(cache.evaluate(playerId, COMBAT_FIVE).orElseThrow().allowed());
        cache.applyCommittedAward(committed);

        ItemUseEligibility after = cache.evaluate(playerId, COMBAT_FIVE).orElseThrow();
        assertTrue(after.allowed());
        assertEquals(5, after.currentSkillLevels().get(COMBAT));
    }

    @Test
    void olderCommittedAwardCannotRollNewerSnapshotBackward() throws Exception {
        UUID playerId = player("CacheOlderAward");
        SkillXpAwardResult levelFour = progression.awardExperience(
                UUID.randomUUID(), playerId, COMBAT, 40, "test.cache"
        );
        progression.awardExperience(UUID.randomUUID(), playerId, COMBAT, 10, "test.cache");

        PaperItemUseEligibilityCache cache = cache(2);
        cache.refresh(playerId);
        assertTrue(cache.evaluate(playerId, COMBAT_FIVE).orElseThrow().allowed());

        cache.applyCommittedAward(levelFour);

        ItemUseEligibility afterReplay = cache.evaluate(playerId, COMBAT_FIVE).orElseThrow();
        assertTrue(afterReplay.allowed());
        assertEquals(5, afterReplay.currentSkillLevels().get(COMBAT));
    }

    @Test
    void invalidationRemovesRestrictedSnapshot() throws Exception {
        UUID playerId = player("CacheDetach");
        progression.awardExperience(UUID.randomUUID(), playerId, COMBAT, 50, "test.cache");
        PaperItemUseEligibilityCache cache = cache(2);
        cache.refresh(playerId);

        assertTrue(cache.evaluate(playerId, COMBAT_FIVE).orElseThrow().allowed());
        cache.invalidate(playerId);

        assertTrue(cache.evaluate(playerId, COMBAT_FIVE).isEmpty());
        assertEquals(0, cache.cachedPlayerCount());
    }

    @Test
    void boundedCacheRejectsAnotherPlayerWithoutEvictingExistingSnapshot() throws Exception {
        UUID first = player("CacheFirst");
        UUID second = player("CacheSecond");
        progression.awardExperience(UUID.randomUUID(), first, COMBAT, 50, "test.cache");
        PaperItemUseEligibilityCache cache = cache(1);
        cache.refresh(first);

        assertThrows(IllegalStateException.class, () -> cache.refresh(second));

        assertEquals(1, cache.cachedPlayerCount());
        assertTrue(cache.evaluate(first, COMBAT_FIVE).orElseThrow().allowed());
        assertTrue(cache.evaluate(second, COMBAT_FIVE).isEmpty());
    }

    private PaperItemUseEligibilityCache cache(int maxPlayers) {
        return new PaperItemUseEligibilityCache(itemCatalog, progressionQuery, maxPlayers);
    }

    private UUID player(String name) throws SQLException {
        return identities.ensurePlayer(UUID.randomUUID(), name);
    }

    private static SkillProgressionDefinition skill(SkillId skillId) {
        ArrayList<Long> thresholds = new ArrayList<>(101);
        for (int level = 0; level <= 100; level++) {
            thresholds.add(level * 10L);
        }
        return new SkillProgressionDefinition(skillId, thresholds);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
