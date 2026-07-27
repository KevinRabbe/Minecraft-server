package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.crafting.CraftExecutionResult;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeCatalog;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeDefinition;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeVersion;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingCommissionCompletionRepository;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingExperienceCatalog;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingExperienceDefinition;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingExperienceFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftingRepository;
import io.github.kevinrabbe.minecraftserver.common.crafting.RecipeIngredient;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionRequest;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRollProfile;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CraftingIntegrityVerifierIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String RAW = "integrity.raw";
    private static final String BAR = "integrity.bar";
    private static final String SWORD = "integrity.sword";
    private static final String BAR_RECIPE = "integrity.bar_refine";
    private static final String SWORD_RECIPE = "integrity.sword_forge";
    private static final SkillId CRAFTING_SKILL = new SkillId("crafting_integrity");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ItemCatalog items;
    private SkillProgressionCatalog skills;
    private CraftRecipeCatalog recipes;
    private CraftingRepository crafting;
    private CraftingExperienceFulfillmentRepository experience;
    private CraftingCommissionRepository commissions;
    private CraftingCommissionCompletionRepository commissionCompletion;
    private CraftingIntegrityVerifier verifier;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                8
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        sessions = new PlayerSessionRepository(dataSource);
        items = new ItemCatalog(List.of(
                commodity(RAW, "RAW_IRON"),
                commodity(BAR, "IRON_INGOT"),
                new ItemDefinition(
                        SWORD,
                        "IRON_SWORD",
                        "Integrity Sword",
                        1,
                        ItemCategory.EQUIPMENT,
                        ItemIdentityKind.INDIVIDUAL
                )
        ));
        skills = new SkillProgressionCatalog(List.of(linearSkill(CRAFTING_SKILL)));
        recipes = new CraftRecipeCatalog(List.of(
                recipe(1, BAR_RECIPE, 2, BAR),
                recipe(1, SWORD_RECIPE, 3, SWORD)
        ), items);
        crafting = new CraftingRepository(dataSource, items, recipes, skills, (player, materials, current, next) -> { });
        CraftingExperienceCatalog xpCatalog = new CraftingExperienceCatalog(List.of(
                new CraftingExperienceDefinition(BAR_RECIPE, 1, CRAFTING_SKILL, 10),
                new CraftingExperienceDefinition(SWORD_RECIPE, 1, CRAFTING_SKILL, 10)
        ), skills);
        experience = new CraftingExperienceFulfillmentRepository(dataSource, xpCatalog, skills);
        commissions = new CraftingCommissionRepository(
                dataSource, items, recipes, skills, (player, materials, current, next) -> { }
        );
        commissionCompletion = new CraftingCommissionCompletionRepository(dataSource, items, recipes, skills);
        verifier = new CraftingIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        truncateCraftingAuthority();
    }

    @AfterEach
    void cleanDatabase() throws SQLException {
        truncateCraftingAuthority();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void realPersonalCommodityIndividualCommissionAndXpEvidenceAreClean() throws Exception {
        PlayerContext commodityCrafter = playerWithSession("CraftCheckA");
        CraftExecutionResult commodityCraft = personalCraft(commodityCrafter, BAR_RECIPE, new byte[]{1});
        experience.fulfill(commodityCraft.craftId());

        PlayerContext individualCrafter = playerWithSession("CraftCheckB");
        CraftExecutionResult individualCraft = personalCraft(individualCrafter, SWORD_RECIPE, new byte[]{2});
        experience.fulfill(individualCraft.craftId());

        PlayerContext requester = playerWithSession("CraftRequest");
        UUID worker = identities.ensurePlayer(UUID.randomUUID(), "CraftWorker");
        var created = commissions.createFunded(
                UUID.randomUUID(),
                requester.session().sessionId(),
                "paper-craft-integrity",
                requester.session().stateVersion(),
                new CraftingCommissionRequest(BAR_RECIPE, 1, Map.of(RAW, 2L), 0),
                "city",
                "commission-board",
                new byte[]{3},
                "craft.integrity_commission_create"
        );
        commissions.accept(UUID.randomUUID(), created.commission().commissionId(), worker);
        var completed = commissionCompletion.complete(
                UUID.randomUUID(),
                created.commission().commissionId(),
                worker,
                "craft.integrity_commission_complete"
        );
        experience.fulfill(completed.craft().craftId());

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void missingPersonalCraftProcessedOperationIsDetected() throws Exception {
        PlayerContext player = playerWithSession("CraftEvidenceA");
        CraftExecutionResult craft = personalCraft(player, BAR_RECIPE, new byte[]{4});
        deleteProcessedOperation(craft.operationId());

        assertContainsOnly("CRAFT_RECORD_EVIDENCE_MISMATCH", craft.craftId());
    }

    @Test
    void corruptedCommodityOutputSourceOperationIsDetected() throws Exception {
        PlayerContext player = playerWithSession("CraftEvidenceB");
        CraftExecutionResult craft = personalCraft(player, BAR_RECIPE, new byte[]{5});
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE pending_commodity_deliveries
                     SET source_operation_id = ?
                     WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, craft.deliveryId());
            assertEquals(1, statement.executeUpdate());
        }

        assertContainsOnly("CRAFT_OUTPUT_EVIDENCE_MISMATCH", craft.craftId());
    }

    @Test
    void fulfillmentWithoutSkillXpEvidenceIsDetected() throws Exception {
        PlayerContext player = playerWithSession("CraftEvidenceC");
        CraftExecutionResult craft = personalCraft(player, BAR_RECIPE, new byte[]{6});
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO craft_experience_fulfillments(craft_id, xp_operation_id)
                     VALUES (?, ?)
                     """)) {
            statement.setObject(1, craft.craftId());
            statement.setObject(2, UUID.randomUUID());
            assertEquals(1, statement.executeUpdate());
        }

        assertContainsOnly("CRAFT_XP_EVIDENCE_MISMATCH", craft.craftId());
    }

    private CraftExecutionResult personalCraft(PlayerContext player, String recipeId, byte[] nextPayload) throws SQLException {
        return crafting.craftFromPlayerState(
                UUID.randomUUID(),
                player.session().sessionId(),
                "paper-craft-integrity",
                player.session().stateVersion(),
                recipeId,
                1,
                "city",
                "forge",
                nextPayload,
                "craft.integrity_execute"
        );
    }

    private PlayerContext playerWithSession(String name) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-craft-integrity", null, LEASE);
        return new PlayerContext(playerId, session);
    }

    private void assertContainsOnly(String expectedCode, UUID craftId) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals(expectedCode, issue.code());
        assertEquals(craftId.toString(), issue.subjectId());
    }

    private void deleteProcessedOperation(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM processed_operations WHERE operation_id = ?
                     """)) {
            statement.setObject(1, operationId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void truncateCraftingAuthority() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        craft_experience_fulfillments,
                        crafting_commission_returns,
                        crafting_commission_materials,
                        crafting_commissions,
                        craft_records,
                        pending_unique_deliveries,
                        pending_commodity_deliveries,
                        item_provenance,
                        item_instances,
                        skill_xp_awards,
                        player_skills,
                        economic_ledger,
                        processed_operations,
                        player_sessions,
                        player_state,
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    private static ItemDefinition commodity(String definitionId, String material) {
        return new ItemDefinition(
                definitionId,
                material,
                definitionId,
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        );
    }

    private static CraftRecipeVersion recipe(int version, String recipeId, long inputQuantity, String outputId) {
        return new CraftRecipeVersion(
                version,
                new CraftRecipeDefinition(
                        recipeId,
                        List.of(new RecipeIngredient(RAW, inputQuantity)),
                        outputId,
                        1,
                        null,
                        0
                ),
                ItemRollProfile.NONE
        );
    }

    private static SkillProgressionDefinition linearSkill(SkillId skillId) {
        ArrayList<Long> thresholds = new ArrayList<>(SkillProgressionDefinition.LONG_TERM_MAX_LEVEL + 1);
        for (int level = 0; level <= SkillProgressionDefinition.LONG_TERM_MAX_LEVEL; level++) {
            thresholds.add(level * 100L);
        }
        return new SkillProgressionDefinition(skillId, thresholds);
    }

    private record PlayerContext(UUID playerId, SessionLease session) { }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for PostgreSQL integration tests");
        }
        return value;
    }
}
