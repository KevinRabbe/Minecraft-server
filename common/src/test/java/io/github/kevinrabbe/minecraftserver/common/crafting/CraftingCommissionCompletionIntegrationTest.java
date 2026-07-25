package io.github.kevinrabbe.minecraftserver.common.crafting;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityBatchEscrowValidator;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionRequest;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionSnapshot;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionStatus;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRollProfile;
import io.github.kevinrabbe.minecraftserver.common.item.RollRange;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CraftingCommissionCompletionIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String IRON = "commission.iron";
    private static final String SWORD = "commission.sword";
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
    private CraftingCommissionRepository commissions;
    private CraftingCommissionCompletionRepository completion;

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
                new ItemDefinition(IRON, "IRON_INGOT", "Commission Iron", 64,
                        ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY),
                new ItemDefinition(SWORD, "IRON_SWORD", "Commission Sword", 1,
                        ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL)
        ));
        skills = new SkillProgressionCatalog(List.of(linearSkill(SMITHING)));
        recipes = new CraftRecipeCatalog(List.of(
                new CraftRecipeVersion(
                        4,
                        new CraftRecipeDefinition(
                                "commission.sword.recipe",
                                List.of(new RecipeIngredient(IRON, 5)),
                                SWORD,
                                1,
                                SMITHING,
                                10
                        ),
                        new ItemRollProfile(Map.of(
                                "damage", new RollRange(10_000, 12_000),
                                "speed", new RollRange(9_500, 10_500)
                        ))
                )
        ), items);
        CommodityBatchEscrowValidator permissive = (playerId, materials, current, next) -> { };
        commissions = new CraftingCommissionRepository(dataSource, items, recipes, skills, permissive);
        completion = new CraftingCommissionCompletionRepository(dataSource, items, recipes, skills);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        crafting_commission_returns,
                        crafting_commission_materials,
                        crafting_commissions,
                        craft_records,
                        pending_unique_deliveries,
                        pending_commodity_deliveries,
                        item_provenance,
                        item_instances,
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
    void completionPaysWorkerAndCraftsRolledOutputToRequesterExactlyOnce() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("CompleteRequester", 10_000, new byte[]{5});
        UUID worker = identities.ensurePlayer(UUID.randomUUID(), "CompleteWorker");
        setSkillExperience(worker, 1_000);
        CraftingCommissionSnapshot accepted = createAndAccept(requester, worker, 1_500);
        UUID operationId = UUID.randomUUID();

        CraftingCommissionCompletionResult first = completion.complete(
                operationId, accepted.commissionId(), worker, "commission.complete");
        CraftingCommissionCompletionResult retry = completion.complete(
                operationId, accepted.commissionId(), worker, "commission.complete");

        assertEquals(first, retry);
        assertEquals(CraftingCommissionStatus.COMPLETED, first.commission().status());
        assertEquals(worker, first.craft().crafterPlayerId());
        assertEquals(requester.playerId(), first.craft().recipientPlayerId());
        assertEquals(SWORD, first.craft().outputDefinitionId());
        assertNotNull(first.craft().itemInstanceId());
        assertEquals(2, first.craft().rollQualityBasisPoints().size());
        first.craft().rollQualityBasisPoints().values().forEach(value -> assertTrue(value >= 0 && value <= 10_000));
        assertEquals(1_500L, wallets.load(worker).balanceMinor());
        assertEquals(8_500L, wallets.load(requester.playerId()).balanceMinor());
        assertUniqueDelivery(first.craft().deliveryId(), requester.playerId(), first.craft().itemInstanceId());
        assertEquals(first.craft().craftId(), completionCraftId(accepted.commissionId()));
        assertEquals(1L, rowCount("craft_records"));
        assertEquals(1L, rowCount("item_instances"));
    }

    @Test
    void wrongWorkerCannotCompleteOrReceivePayment() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("WrongWorkerRequester", 10_000, new byte[]{5});
        UUID worker = identities.ensurePlayer(UUID.randomUUID(), "AcceptedWorker");
        UUID outsider = identities.ensurePlayer(UUID.randomUUID(), "WrongWorker");
        setSkillExperience(worker, 1_000);
        setSkillExperience(outsider, 1_000);
        CraftingCommissionSnapshot accepted = createAndAccept(requester, worker, 1_000);

        assertThrows(CraftingException.class,
                () -> completion.complete(UUID.randomUUID(), accepted.commissionId(), outsider, "commission.complete"));

        assertEquals(0L, wallets.load(worker).balanceMinor());
        assertEquals(0L, wallets.load(outsider).balanceMinor());
        assertEquals(0L, rowCount("craft_records"));
        assertEquals(CraftingCommissionStatus.ACCEPTED, commissions.load(accepted.commissionId()).status());
    }

    @Test
    void twoConcurrentCompletionOperationsCanProduceOnlyOnePaymentAndOneItem() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("RaceRequester", 10_000, new byte[]{5});
        UUID worker = identities.ensurePlayer(UUID.randomUUID(), "RaceWorker");
        setSkillExperience(worker, 1_000);
        CraftingCommissionSnapshot accepted = createAndAccept(requester, worker, 2_000);

        int successes = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CraftingCommissionCompletionResult> a = executor.submit(
                    () -> completion.complete(UUID.randomUUID(), accepted.commissionId(), worker, "commission.complete"));
            Future<CraftingCommissionCompletionResult> b = executor.submit(
                    () -> completion.complete(UUID.randomUUID(), accepted.commissionId(), worker, "commission.complete"));
            for (Future<CraftingCommissionCompletionResult> future : List.of(a, b)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof CraftingException);
                }
            }
        }

        assertEquals(1, successes);
        assertEquals(2_000L, wallets.load(worker).balanceMinor());
        assertEquals(1L, rowCount("craft_records"));
        assertEquals(1L, rowCount("item_instances"));
        assertEquals(CraftingCommissionStatus.COMPLETED, commissions.load(accepted.commissionId()).status());
    }

    @Test
    void databaseRejectsCompletedCommissionWithoutMatchingCraftEvidence() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("EvidenceRequester", 10_000, new byte[]{5});
        UUID worker = identities.ensurePlayer(UUID.randomUUID(), "EvidenceWorker");
        setSkillExperience(worker, 1_000);
        CraftingCommissionSnapshot accepted = createAndAccept(requester, worker, 1_000);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE crafting_commissions
                     SET status = 'COMPLETED',
                         settle_operation_id = ?,
                         completion_craft_id = ?,
                         settled_at = NOW()
                     WHERE commission_id = ?
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, accepted.commissionId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
        assertEquals(CraftingCommissionStatus.ACCEPTED, commissions.load(accepted.commissionId()).status());
    }

    @Test
    void terminalCommissionRowCannotBeRewrittenAfterCompletion() throws Exception {
        PlayerContext requester = fundedPlayerWithSession("ImmutableRequester", 10_000, new byte[]{5});
        UUID worker = identities.ensurePlayer(UUID.randomUUID(), "ImmutableWorker");
        setSkillExperience(worker, 1_000);
        CraftingCommissionSnapshot accepted = createAndAccept(requester, worker, 500);
        completion.complete(UUID.randomUUID(), accepted.commissionId(), worker, "commission.complete");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE crafting_commissions
                     SET payment_minor = payment_minor + 1
                     WHERE commission_id = ?
                     """)) {
            statement.setObject(1, accepted.commissionId());
            assertThrows(SQLException.class, statement::executeUpdate);
        }
        assertEquals(500L, commissions.load(accepted.commissionId()).paymentMinor());
    }

    private CraftingCommissionSnapshot createAndAccept(PlayerContext requester, UUID worker, long payment)
            throws SQLException {
        CraftingCommissionRequest request = new CraftingCommissionRequest(
                "commission.sword.recipe", 4, Map.of(IRON, 5L), payment);
        CraftingCommissionSnapshot open = commissions.createFunded(
                UUID.randomUUID(), requester.session().sessionId(), "paper-a", requester.session().stateVersion(),
                request, "city", "forge", new byte[0], "commission.create").commission();
        return commissions.accept(UUID.randomUUID(), open.commissionId(), worker);
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

    private void assertUniqueDelivery(UUID deliveryId, UUID recipient, UUID itemId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT recipient_player_id, item_instance_id, status
                     FROM pending_unique_deliveries WHERE delivery_id = ?
                     """)) {
            statement.setObject(1, deliveryId);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(recipient, row.getObject(1, UUID.class));
                assertEquals(itemId, row.getObject(2, UUID.class));
                assertEquals("PENDING", row.getString(3));
            }
        }
    }

    private UUID completionCraftId(UUID commissionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT completion_craft_id FROM crafting_commissions WHERE commission_id = ?")) {
            statement.setObject(1, commissionId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private long rowCount(String table) throws SQLException {
        if (!List.of("craft_records", "item_instances").contains(table)) {
            throw new IllegalArgumentException("unsupported table: " + table);
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next();
            return row.getLong(1);
        }
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
