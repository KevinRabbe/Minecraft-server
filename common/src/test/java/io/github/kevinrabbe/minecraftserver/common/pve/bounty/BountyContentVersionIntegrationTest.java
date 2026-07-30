package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BountyContentVersionIntegrationTest {
    private static final BountyFamilyId FAMILY = new BountyFamilyId("zombie");
    private static final String SOURCE = "starter_pve.zombie";
    private static final String V1_REWARD = "material.zombie_essence_v1";
    private static final String V2_REWARD = "material.zombie_essence_v2";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private CoinWalletRepository wallets;

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
        wallets = new CoinWalletRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        bounty_summons,
                        bounty_pouch_balances,
                        bounty_pouches,
                        bounty_contracts,
                        economic_ledger,
                        processed_operations,
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
    void activeV1ContractKeepsV1BossAndRewardsAfterV2DeployWhileNewContractUsesV2() throws Exception {
        BountyContentCatalog v1 = content(v1Tier(), Map.of(V1_REWARD, 2L));
        BountyRepository beforeDeploy = repository(v1);
        UUID oldPlayer = fundedPlayer("BountyV1");

        BountyContractStartResult startedV1 = beforeDeploy.startContract(
                UUID.randomUUID(), oldPlayer, FAMILY, 1, "bounty.start"
        );
        assertEquals(1, startedV1.contract().contentVersion());
        BountyContractSnapshot readyV1 = beforeDeploy.recordEligibleKills(
                UUID.randomUUID(), startedV1.contract().contractId(), oldPlayer, 1, "bounty.progress"
        );

        BountyContentCatalog afterDeployContent = new BountyContentCatalog(List.of(
                configured(v1Tier(), Map.of(V1_REWARD, 2L)),
                configured(v2Tier(), Map.of(V2_REWARD, 7L))
        ));
        BountyRepository afterDeploy = repository(afterDeployContent);

        BountySummonPrepareResult preparedV1 = afterDeploy.prepareSummon(
                UUID.randomUUID(), readyV1.contractId(), oldPlayer, "bounty.prepare"
        );
        assertEquals(1, preparedV1.contract().contentVersion());
        assertEquals("boss.zombie.v1", preparedV1.bossDefinitionId());

        BountySummonLeaseResult activeV1 = afterDeploy.claimSummon(
                UUID.randomUUID(), preparedV1.summon().summonId(), "paper-a", "bounty.claim"
        );
        afterDeploy.completeBoss(
                UUID.randomUUID(), activeV1.summon().summonId(), "paper-a",
                activeV1.summon().stateVersion(), "bounty.complete"
        );
        assertEquals(2L, pouchQuantity(oldPlayer, V1_REWARD));
        assertEquals(0L, pouchQuantityOrZero(oldPlayer, V2_REWARD));

        UUID newPlayer = fundedPlayer("BountyV2");
        BountyContractStartResult startedV2 = afterDeploy.startContract(
                UUID.randomUUID(), newPlayer, FAMILY, 1, "bounty.start"
        );
        assertEquals(2, startedV2.contract().contentVersion());
        BountyContractSnapshot readyV2 = afterDeploy.recordEligibleKills(
                UUID.randomUUID(), startedV2.contract().contractId(), newPlayer, 2, "bounty.progress"
        );
        BountySummonPrepareResult preparedV2 = afterDeploy.prepareSummon(
                UUID.randomUUID(), readyV2.contractId(), newPlayer, "bounty.prepare"
        );
        assertEquals("boss.zombie.v2", preparedV2.bossDefinitionId());
    }

    private BountyRepository repository(BountyContentCatalog content) {
        return new BountyRepository(dataSource, content.tiers(), content, Duration.ofSeconds(30));
    }

    private BountyContentCatalog content(BountyTierDefinition tier, Map<String, Long> rewards) {
        return new BountyContentCatalog(List.of(configured(tier, rewards)));
    }

    private BountyContentCatalog.ConfiguredTier configured(
            BountyTierDefinition tier,
            Map<String, Long> rewards
    ) {
        return new BountyContentCatalog.ConfiguredTier(tier, List.of(SOURCE), rewards);
    }

    private BountyTierDefinition v1Tier() {
        return new BountyTierDefinition(
                FAMILY, 1, 1, 100L, 1, "boss.zombie.v1", List.of(V1_REWARD)
        );
    }

    private BountyTierDefinition v2Tier() {
        return new BountyTierDefinition(
                FAMILY, 1, 2, 200L, 2, "boss.zombie.v2", List.of(V2_REWARD)
        );
    }

    private UUID fundedPlayer(String name) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        wallets.creditFromSystem(UUID.randomUUID(), playerId, 10_000L, "test.funding");
        return playerId;
    }

    private long pouchQuantity(UUID playerId, String definitionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT quantity
                     FROM bounty_pouch_balances
                     WHERE player_id = ? AND family_id = ? AND commodity_definition_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, FAMILY.value());
            statement.setString(3, definitionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new AssertionError("missing bounty pouch balance for " + definitionId);
                return row.getLong("quantity");
            }
        }
    }

    private long pouchQuantityOrZero(UUID playerId, String definitionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT quantity
                     FROM bounty_pouch_balances
                     WHERE player_id = ? AND family_id = ? AND commodity_definition_id = ?
                     """)) {
            statement.setObject(1, playerId);
            statement.setString(2, FAMILY.value());
            statement.setString(3, definitionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong("quantity") : 0L;
            }
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }
}
