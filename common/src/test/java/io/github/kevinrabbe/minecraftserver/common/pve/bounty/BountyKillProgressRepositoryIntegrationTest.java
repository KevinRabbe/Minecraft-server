package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogLoader;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceHarvestFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceHarvestResult;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceDefinition;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceId;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceSnapshot;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceType;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceView;
import io.github.kevinrabbe.minecraftserver.common.world.resource.entity.ResourceEntitySpawnRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.entity.ResourceEntitySpawnSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BountyKillProgressRepositoryIntegrationTest {
    private static final BountyFamilyId FAMILY = new BountyFamilyId("zombie");
    private static final String DROP = "material.rotten_flesh";
    private static final String SOURCE_DEFINITION = "starter_pve.zombie";
    private static final SkillId COMBAT = new SkillId("combat");
    private static final UUID INSTANCE_ID = UUID.randomUUID();

    private static HikariDataSource dataSource;
    private static ItemCatalog items;
    private static ResourceSourceCatalog sourceCatalog;
    private static BountyKillProgressRepository progress;
    private static ResourceSourceRepository sources;
    private static ResourceEntitySpawnRepository spawns;
    private static ResourceHarvestFulfillmentRepository fulfillment;

    @BeforeAll
    static void openDatabase() throws Exception {
        String jdbcUrl = requireEnvironment("TEST_DATABASE_URL");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(requireEnvironment("TEST_DATABASE_USER"));
        config.setPassword(requireEnvironment("TEST_DATABASE_PASSWORD"));
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        new Database(dataSource).migrate();

        items = new ItemCatalogLoader().loadResource("/content/items.json");
        SkillProgressionCatalog skillCatalog = new SkillProgressionCatalog(List.of(curve(COMBAT)));
        ResourceSourceDefinition sourceDefinition = new ResourceSourceDefinition(
                SOURCE_DEFINITION,
                ResourceSourceType.ENTITY,
                DROP,
                1,
                1,
                COMBAT,
                0,
                Duration.ZERO
        );
        sourceCatalog = new ResourceSourceCatalog(List.of(sourceDefinition), items, skillCatalog);
        sources = new ResourceSourceRepository(dataSource, sourceCatalog);
        spawns = new ResourceEntitySpawnRepository(dataSource, sourceCatalog);
        fulfillment = new ResourceHarvestFulfillmentRepository(dataSource, skillCatalog);

        BountyTierDefinition tier = new BountyTierDefinition(
                FAMILY,
                1,
                1,
                100,
                3,
                "boss.zombie.t1",
                List.of(DROP)
        );
        BountyContentCatalog bountyContent = new BountyContentCatalog(List.of(
                new BountyContentCatalog.ConfiguredTier(
                        tier,
                        List.of(SOURCE_DEFINITION),
                        Map.of(DROP, 1L)
                )
        ));
        progress = new BountyKillProgressRepository(dataSource, bountyContent);
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void resetDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE bounty_managed_kill_progress, bounty_summons, bounty_contracts, "
                    + "resource_harvest_fulfillments, resource_entity_kill_claims, resource_entity_spawns, resource_harvests, "
                    + "resource_sources, processed_operations, player_skill_xp_events, player_skills, player_sessions, "
                    + "player_states, wallets, players, economic_ledger, asset_ledger RESTART IDENTITY CASCADE");
        }
    }

    @Test
    void killWithoutActiveHuntIsPermanentNoOpAcrossLaterContract() throws Exception {
        PlayerContext player = createPlayer("no-hunt");
        UUID killOperationId = settleManagedKill(player, "no-hunt-kill", 1);

        BountyKillProgressResult first = progress.recordManagedKill(
                killOperationId,
                player.playerId(),
                SOURCE_DEFINITION,
                FAMILY,
                1,
                "bounty.managed_kill"
        );
        assertFalse(first.applied());
        assertEquals(1L, bridgeCount(killOperationId));

        UUID contractId = UUID.randomUUID();
        insertActiveContract(contractId, player.playerId(), 3);
        BountyKillProgressResult replay = progress.recordManagedKill(
                killOperationId,
                player.playerId(),
                SOURCE_DEFINITION,
                FAMILY,
                1,
                "bounty.managed_kill"
        );
        assertEquals(first, replay);
        assertEquals(0, contractProgress(contractId));
    }

    @Test
    void distinctManagedKillsAdvanceExactlyOnceAndUnlockSummon() throws Exception {
        PlayerContext player = createPlayer("active");
        UUID contractId = UUID.randomUUID();
        insertActiveContract(contractId, player.playerId(), 2);

        UUID killOne = settleManagedKill(player, "kill-one", 1);
        UUID killTwo = settleManagedKill(player, "kill-two", 1);

        BountyKillProgressResult one = progress.recordManagedKill(
                killOne,
                player.playerId(),
                SOURCE_DEFINITION,
                FAMILY,
                1,
                "bounty.managed_kill"
        );
        assertTrue(one.applied());
        assertEquals(1, one.contract().eligibleKillProgress());
        assertEquals(BountyContractStatus.ACTIVE_HUNT, one.contract().status());

        BountyKillProgressResult replay = progress.recordManagedKill(
                killOne,
                player.playerId(),
                SOURCE_DEFINITION,
                FAMILY,
                1,
                "bounty.managed_kill"
        );
        assertEquals(one, replay);
        assertEquals(1, contractProgress(contractId));

        BountyKillProgressResult two = progress.recordManagedKill(
                killTwo,
                player.playerId(),
                SOURCE_DEFINITION,
                FAMILY,
                1,
                "bounty.managed_kill"
        );
        assertTrue(two.applied());
        assertEquals(2, two.contract().eligibleKillProgress());
        assertEquals(BountyContractStatus.SUMMON_READY, two.contract().status());
        assertEquals(1, two.contract().summonAuthorizationsRemaining());
        assertEquals(2L, bridgeCountAll());
    }

    @Test
    void recoveryScanReturnsOnlyUnclassifiedEligibleEntityHarvests() throws Exception {
        PlayerContext player = createPlayer("recovery");
        UUID classified = settleManagedKill(player, "classified", 1);
        UUID pending = settleManagedKill(player, "pending", 1);
        progress.recordManagedKill(
                classified,
                player.playerId(),
                SOURCE_DEFINITION,
                FAMILY,
                1,
                "bounty.managed_kill"
        );

        List<BountyManagedKillCandidate> candidates = progress.listUnclassifiedManagedKills(
                List.of(SOURCE_DEFINITION),
                10
        );

        assertEquals(1, candidates.size());
        assertEquals(pending, candidates.getFirst().resourceKillOperationId());
        assertEquals(player.playerId(), candidates.getFirst().playerId());
        assertEquals(SOURCE_DEFINITION, candidates.getFirst().sourceDefinitionId());
    }

    private PlayerContext createPlayer(String suffix) throws Exception {
        PlayerIdentityRepository identities = new PlayerIdentityRepository(dataSource);
        PlayerSessionRepository sessions = new PlayerSessionRepository(dataSource);
        UUID minecraftUuid = UUID.nameUUIDFromBytes(("minecraft-" + suffix).getBytes());
        UUID playerId = identities.findOrCreate(minecraftUuid, "Player-" + suffix);
        SessionLease session = sessions.openSession(
                playerId,
                "paper-a",
                Duration.ofMinutes(5),
                "city",
                INSTANCE_ID
        );
        return new PlayerContext(playerId, session);
    }

    private UUID settleManagedKill(PlayerContext player, String suffix, long spawnId) throws Exception {
        ResourceSourceId sourceId = new ResourceSourceId(
                UUID.nameUUIDFromBytes(("source-" + suffix).getBytes(StandardCharsets.UTF_8))
        );
        ResourceSourceSnapshot source = sources.registerSource(
                sourceId,
                INSTANCE_ID,
                SOURCE_DEFINITION,
                true
        );
        UUID entityUuid = UUID.nameUUIDFromBytes(("entity-" + suffix).getBytes(StandardCharsets.UTF_8));
        ResourceEntitySpawnSnapshot spawn = spawns.prepareSpawn(
                sourceId,
                source.cycleVersion(),
                spawnId,
                entityUuid,
                Duration.ofMinutes(5)
        );
        spawns.confirmSpawn(spawn.sourceId(), spawn.cycleVersion(), spawn.spawnId(), spawn.entityUuid());
        UUID killOperationId = UUID.nameUUIDFromBytes(("kill-op-" + suffix).getBytes(StandardCharsets.UTF_8));
        ResourceHarvestResult result = spawns.claimKill(
                killOperationId,
                sourceId,
                source.cycleVersion(),
                spawnId,
                entityUuid,
                player.session().sessionId(),
                player.playerId(),
                "paper-a",
                player.session().stateVersion(),
                null,
                null,
                "resource.entity_kill"
        );
        fulfillment.fulfill(killOperationId, "resource.entity_kill");
        assertEquals(player.playerId(), result.playerId());
        return killOperationId;
    }

    private void insertActiveContract(UUID contractId, UUID playerId, int requiredKills) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO bounty_contracts(
                         contract_id,
                         player_id,
                         family_id,
                         tier,
                         status,
                         eligible_kill_progress,
                         required_eligible_kills,
                         summon_authorizations_remaining,
                         fee_operation_id,
                         state_version
                     ) VALUES (?, ?, ?, 1, 'ACTIVE_HUNT', 0, ?, 0, ?, 0)
                     """)) {
            statement.setObject(1, contractId);
            statement.setObject(2, playerId);
            statement.setString(3, FAMILY.value());
            statement.setInt(4, requiredKills);
            statement.setObject(5, UUID.randomUUID());
            statement.executeUpdate();
        }
    }

    private int contractProgress(UUID contractId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT eligible_kill_progress FROM bounty_contracts WHERE contract_id = ?"
             )) {
            statement.setObject(1, contractId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private long bridgeCount(UUID resourceKillOperationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM bounty_managed_kill_progress
                     WHERE resource_kill_operation_id = ?
                     """)) {
            statement.setObject(1, resourceKillOperationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long bridgeCountAll() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM bounty_managed_kill_progress")) {
            row.next();
            return row.getLong(1);
        }
    }

    private long processedCount(UUID operationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM processed_operations WHERE operation_id = ?"
             )) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static SkillProgressionDefinition curve(SkillId skillId) {
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
