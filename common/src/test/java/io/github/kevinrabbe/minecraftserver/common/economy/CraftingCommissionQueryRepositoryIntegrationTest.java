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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CraftingCommissionQueryRepositoryIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String IRON = "query.iron";
    private static final String COAL = "query.coal";
    private static final String TOOL = "query.tool";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private CoinWalletRepository wallets;
    private CraftingCommissionRepository commissions;
    private CraftingCommissionQueryRepository queries;

    @BeforeAll
    void openDatabase() {
        database = Database.open(new DatabaseConfig(
                requireEnvironment("TEST_DATABASE_URL"),
                requireEnvironment("TEST_DATABASE_USER"),
                requireEnvironment("TEST_DATABASE_PASSWORD"),
                6
        ));
        database.migrate();
        dataSource = database.dataSource();
        identities = new PlayerIdentityRepository(dataSource);
        sessions = new PlayerSessionRepository(dataSource);
        states = new PlayerStateRepository(dataSource);
        wallets = new CoinWalletRepository(dataSource);

        ItemCatalog items = new ItemCatalog(List.of(
                new ItemDefinition(IRON, "IRON_INGOT", "Query Iron", 64, ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY),
                new ItemDefinition(COAL, "COAL", "Query Coal", 64, ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY),
                new ItemDefinition(TOOL, "IRON_PICKAXE", "Query Tool", 1, ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL)
        ));
        CraftRecipeCatalog recipes = new CraftRecipeCatalog(List.of(
                new CraftRecipeVersion(
                        1,
                        new CraftRecipeDefinition(
                                "query.tool",
                                List.of(new RecipeIngredient(IRON, 5), new RecipeIngredient(COAL, 2)),
                                TOOL,
                                1,
                                null,
                                0
                        ),
                        ItemRollProfile.NONE
                )
        ), items);
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(linearSkill(new SkillId("queryskill"))));
        commissions = new CraftingCommissionRepository(
                dataSource,
                items,
                recipes,
                skills,
                (playerId, materials, current, next) -> { }
        );
        queries = new CraftingCommissionQueryRepository(dataSource);
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
    void browseShowsOnlyOpenCommissionsWithCompleteTerms() throws Exception {
        PlayerContext firstRequester = fundedPlayerWithSession("QueryFirst", new byte[]{9});
        PlayerContext openRequester = fundedPlayerWithSession("QueryOpen", new byte[]{8});
        UUID worker = identities.ensurePlayer(UUID.randomUUID(), "QueryWorker");

        CraftingCommissionSnapshot accepted = create(firstRequester, 1_500).commission();
        commissions.accept(UUID.randomUUID(), accepted.commissionId(), worker);
        CraftingCommissionSnapshot open = create(openRequester, 2_250).commission();

        List<CraftingCommissionBrowseEntry> entries = queries.listOpen(10);

        assertEquals(1, entries.size());
        CraftingCommissionBrowseEntry entry = entries.getFirst();
        assertEquals(open.commissionId(), entry.commissionId());
        assertEquals(openRequester.playerId(), entry.requesterPlayerId());
        assertEquals("query.tool", entry.recipeId());
        assertEquals(1, entry.recipeVersion());
        assertEquals(Map.of(IRON, 5L, COAL, 2L), entry.materialQuantities());
        assertEquals(2_250L, entry.paymentMinor());
    }

    @Test
    void browseLimitIsStrictlyBounded() {
        assertThrows(IllegalArgumentException.class, () -> queries.listOpen(0));
        assertThrows(IllegalArgumentException.class, () -> queries.listOpen(101));
    }

    private CraftingCommissionCreateResult create(PlayerContext requester, long paymentMinor) throws SQLException {
        return commissions.createFunded(
                UUID.randomUUID(),
                requester.session().sessionId(),
                "paper-a",
                requester.session().stateVersion(),
                new CraftingCommissionRequest(
                        "query.tool",
                        1,
                        Map.of(IRON, 5L, COAL, 2L),
                        paymentMinor
                ),
                "city",
                "forge",
                new byte[]{1},
                "commission.query_create"
        );
    }

    private PlayerContext fundedPlayerWithSession(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 10_000, "test.funding");
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long stateVersion = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "forge", payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(stateVersion, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
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
