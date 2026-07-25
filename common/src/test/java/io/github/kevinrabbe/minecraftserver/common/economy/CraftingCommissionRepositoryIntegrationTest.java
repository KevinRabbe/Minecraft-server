package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeCatalog;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeDefinition;
import io.github.kevinrabbe.minecraftserver.common.crafting.CraftRecipeVersion;
import io.github.kevinrabbe.minecraftserver.common.crafting.RecipeIngredient;
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
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CraftingCommissionRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String IRON = "craft.iron";
    private static final String COAL = "craft.coal";
    private static final String TOOL = "craft.tool";
    private static final SkillId SMITHING = new SkillId("smithing");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private CoinWalletRepository wallets;
    private ItemCatalog items;
    private CraftRecipeCatalog recipes;
    private SkillProgressionCatalog skills;

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
        states = new PlayerStateRepository(dataSource);
        wallets = new CoinWalletRepository(dataSource);
        items = new ItemCatalog(List.of(
                commodity(IRON, "IRON_INGOT"),
                commodity(COAL, "COAL"),
                new ItemDefinition(TOOL, "IRON_PICKAXE", "Craft Tool", 1,
                        ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL)
        ));
        skills = new SkillProgressionCatalog(List.of(linearSkill(SMITHING)));
        recipes = new CraftRecipeCatalog(List.of(
                new CraftRecipeVersion(
                        1,
                        new CraftRecipeDefinition(
                                "tool.recipe",
                                List.of(new RecipeIngredient(IRON, 5), new RecipeIngredient(COAL, 2)),
                                TOOL,
                                1,
                                null,
                                0
                        ),
                        ItemRollProfile.NONE
                ),
                new CraftRecipeVersion(
                        2,
                        new CraftRecipeDefinition(
                                "tool.skilled",
                                List.of(new RecipeIngredient(IRON, 5)),
                                TOOL,
                                1,
                                SMITHING,
                                10
                        ),
                        ItemRollProfile.NONE
                )
        ), items);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        crafting_commission_returns,
                        crafting_commission_materials,
                        crafting_commissions,
                        pending_commodity_deliveries,
                        economic_ledger,
                        processed_operations,
                        player_skills,
                        player_sessions,
                        player_state,
                        player_names,
                        wallets,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void createEscrowsOnlyCanonicalRecipeMaterialsAndPaymentExactlyOnce() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CommRequester", 10_000, new byte[]{9, 4});
        AtomicInteger validations = new AtomicInteger();
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> {
            validations.incrementAndGet();
            assertEquals(Map.of(IRON, 5L, COAL, 2L), materials);
            assertArrayEquals(new byte[]{9, 4}, current);
            assertArrayEquals(new byte[]{4, 2}, next);
        });
        UUID operationId = UUID.randomUUID();
        CraftingCommissionRequest request = request("tool.recipe", 1, Map.of(IRON, 5L, COAL, 2L), 2_000);

        CraftingCommissionCreateResult first = commissions.createFunded(
                operationId, requester.session().sessionId(), "paper-a", requester.session().stateVersion(),
                request, "city", "forge", new byte[]{4, 2}, "commission.create"
        );
        CraftingCommissionCreateResult retry = commissions.createFunded(
                operationId, requester.session().sessionId(), "paper-a", requester.session().stateVersion(),
                request, "city", "forge", new byte[]{4, 2}, "commission.create"
        );

        assertEquals(first, retry);
        assertEquals(1, validations.get());
        assertEquals(8_000L, wallets.load(requester.playerId()).balanceMinor());
        assertEquals(Map.of(IRON, 5L, COAL, 2L), first.commission().materialQuantities());
        assertArrayEquals(new byte[]{4, 2}, states.load(requester.playerId()).statePayload());
    }

    @Test
    void nonCanonicalMaterialSetIsRejectedBeforeAnyValueMoves() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("WrongMaterials", 10_000, new byte[]{9});
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> { });
        CraftingCommissionRequest wrong = request("tool.recipe", 1, Map.of(IRON, 4L, COAL, 2L), 2_000);

        assertThrows(CraftingCommissionException.class, () -> commissions.createFunded(
                UUID.randomUUID(), requester.session().sessionId(), "paper-a", requester.session().stateVersion(),
                wrong, "city", "forge", new byte[]{3}, "commission.create"
        ));

        assertEquals(10_000L, wallets.load(requester.playerId()).balanceMinor());
        assertArrayEquals(new byte[]{9}, states.load(requester.playerId()).statePayload());
        assertEquals(0L, tableCount("crafting_commissions"));
    }

    @Test
    void validatorFailureRollsBackAllCommissionEscrow() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CommRollback", 10_000, new byte[]{9});
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> {
            throw new CraftingCommissionException("invalid inventory transition");
        });

        assertThrows(CraftingCommissionException.class, () -> commissions.createFunded(
                UUID.randomUUID(), requester.session().sessionId(), "paper-a", requester.session().stateVersion(),
                request("tool.recipe", 1, Map.of(IRON, 5L, COAL, 2L), 2_000),
                "city", "forge", new byte[]{1}, "commission.create"
        ));
        assertEquals(10_000L, wallets.load(requester.playerId()).balanceMinor());
        assertEquals(0L, tableCount("crafting_commissions"));
        assertEquals(0L, tableCount("crafting_commission_materials"));
    }

    @Test
    void acceptanceRequiresQualifiedDistinctWorkerAndOnlyOneWorkerWins() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("AcceptRequester", 10_000, new byte[]{9});
        UUID workerA = identities.ensurePlayer(UUID.randomUUID(), "WorkerA");
        UUID workerB = identities.ensurePlayer(UUID.randomUUID(), "WorkerB");
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> { });
        CraftingCommissionSnapshot open = commissions.createFunded(
                UUID.randomUUID(), requester.session().sessionId(), "paper-a", requester.session().stateVersion(),
                request("tool.recipe", 1, Map.of(IRON, 5L, COAL, 2L), 1_000),
                "city", "forge", new byte[]{1}, "commission.create"
        ).commission();

        assertThrows(CraftingCommissionException.class,
                () -> commissions.accept(UUID.randomUUID(), open.commissionId(), requester.playerId()));

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CraftingCommissionSnapshot> a = executor.submit(
                    () -> commissions.accept(UUID.randomUUID(), open.commissionId(), workerA));
            Future<CraftingCommissionSnapshot> b = executor.submit(
                    () -> commissions.accept(UUID.randomUUID(), open.commissionId(), workerB));
            for (Future<CraftingCommissionSnapshot> future : List.of(a, b)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof CraftingCommissionException);
                }
            }
        }
        assertEquals(1, successes);
        assertEquals(CraftingCommissionStatus.ACCEPTED, commissions.load(open.commissionId()).status());
    }

    @Test
    void skillGatedCommissionCannotBeAcceptedByUnderleveledWorker() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("SkillRequester", 10_000, new byte[]{9});
        UUID worker = identities.ensurePlayer(UUID.randomUUID(), "LowSkillWorker");
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> { });
        CraftingCommissionSnapshot open = commissions.createFunded(
                UUID.randomUUID(), requester.session().sessionId(), "paper-a", requester.session().stateVersion(),
                request("tool.skilled", 2, Map.of(IRON, 5L), 1_000),
                "city", "forge", new byte[]{4}, "commission.create"
        ).commission();

        assertThrows(CraftingCommissionException.class,
                () -> commissions.accept(UUID.randomUUID(), open.commissionId(), worker));
        assertEquals(CraftingCommissionStatus.OPEN, commissions.load(open.commissionId()).status());

        setSkillExperience(worker, 1_000);
        assertEquals(CraftingCommissionStatus.ACCEPTED,
                commissions.accept(UUID.randomUUID(), open.commissionId(), worker).status());
    }

    @Test
    void openCancellationReturnsPaymentAndEveryMaterialExactlyOnce() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CancelRequester", 10_000, new byte[]{9});
        CraftingCommissionRepository commissions = repository((playerId, materials, current, next) -> { });
        CraftingCommissionSnapshot open = commissions.createFunded(
                UUID.randomUUID(), requester.session().sessionId(), "paper-a", requester.session().stateVersion(),
                request("tool.recipe", 1, Map.of(IRON, 5L, COAL, 2L), 2_000),
                "city", "forge", new byte[]{1}, "commission.create"
        ).commission();
        UUID cancelOperation = UUID.randomUUID();

        CraftingCommissionCancelResult first = commissions.cancelOpen(
                cancelOperation, open.commissionId(), requester.playerId(), "commission.cancel");
        CraftingCommissionCancelResult retry = commissions.cancelOpen(
                cancelOperation, open.commissionId(), requester.playerId(), "commission.cancel");

        assertEquals(first, retry);
        assertEquals(CraftingCommissionStatus.CANCELLED, first.commission().status());
        assertEquals(10_000L, wallets.load(requester.playerId()).balanceMinor());
        assertEquals(2, first.materialReturns().size());
        assertEquals(2L, tableCount("pending_commodity_deliveries"));
    }

    private CraftingCommissionRepository repository(CommodityBatchEscrowValidator validator) {
        return new CraftingCommissionRepository(dataSource, items, recipes, skills, validator);
    }

    private PlayerContext fundedPlayerWithSession(String name, long amount, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, amount, "test.funding");
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long stateVersion = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "forge", payload);
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(stateVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void setSkillExperience(UUID playerId, long experience) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO player_skills(player_id, skill_id, experience, state_version)
                     VALUES (?, ?, ?, 0)
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, SMITHING.value());
            statement.setLong(3, experience);
            statement.executeUpdate();
        }
    }

    private long tableCount(String table) throws SQLException {
        if (!List.of("crafting_commissions", "crafting_commission_materials", "pending_commodity_deliveries").contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static CraftingCommissionRequest request(
            String recipeId, int recipeVersion, Map<String, Long> materials, long paymentMinor) {
        return new CraftingCommissionRequest(recipeId, recipeVersion, materials, paymentMinor);
    }

    private static ItemDefinition commodity(String id, String material) {
        return new ItemDefinition(id, material, id, 64, ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY);
    }

    private static SkillProgressionDefinition linearSkill(SkillId skillId) {
        ArrayList<Long> thresholds = new ArrayList<>();
        for (int level = 0; level <= 100; level++) thresholds.add(level * 100L);
        return new SkillProgressionDefinition(skillId, thresholds);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }

    private record PlayerContext(UUID playerId, SessionLease session) { }
}
