package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntityKillClaim;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntitySpawnRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceEntitySpawnSnapshot;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceGatheringService;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceHarvestFulfillmentRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceDefinition;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceRepository;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceSnapshot;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class BountyKillProgressRepositoryIntegrationTest {
    private static final Duration SESSION_LEASE = Duration.ofSeconds(30);
    private static final BountyFamilyId FAMILY = new BountyFamilyId("zombie");
    private static final String ZONE = "starter_pve";
    private static final String TEMPLATE = "pve-v1";
    private static final String DROP = "material.rotten_flesh";
    private static final String SOURCE_DEFINITION = "starter_pve.zombie";
    private static final SkillId COMBAT = new SkillId("combat");

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ResourceSourceRepository sources;
    private ResourceEntitySpawnRepository entitySpawns;
    private ResourceGatheringService gathering;
    private BountyKillProgressRepository progress;

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

        ItemCatalog items = new ItemCatalog(List.of(new ItemDefinition(
                DROP,
                "ROTTEN_FLESH",
                "Rotten Flesh",
                64,
                ItemCategory.MATERIALS,
                ItemIdentityKind.COMMODITY
        )));
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(curve(COMBAT)));
        ResourceSourceCatalog sourceCatalog = new ResourceSourceCatalog(
                List.of(new ResourceSourceDefinition(
                        SOURCE_DEFINITION,
                        ZONE,
                        TEMPLATE,
                        DROP,
                        1,
                        COMBAT,
                        10,
                        Duration.ofMillis(1)
                )),
                items,
                skills
        );
        sources = new ResourceSourceRepository(dataSource, sourceCatalog);
        entitySpawns = new ResourceEntitySpawnRepository(dataSource, sourceCatalog);
        gathering = new ResourceGatheringService(
                sources,
                new ResourceHarvestFulfillmentRepository(dataSource, skills)
        );
        BountyTierDefinition tier = new BountyTierDefinition(
                FAMILY,
                1,
                0L,
                1,
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

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        bounty_managed_kill_progress,
                        bounty_summons,
                        bounty_contracts,
                        resource_entity_kill_claims,
                        resource_entity_spawns,
                        resource_entity_sources,
                        resource_harvest_fulfillments,
                        resource_harvests,
                        resource_sources,
                        skill_xp_awards,
                        pending_commodity_deliveries,
                        economic_ledger,
                        processed_operations,
                        player_skills,
                        player_sessions,
                        zone_instances,
                        backends,
                        player_names,
                        player_state,
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
    void killWithoutActiveHuntIsPermanentNoOpAcrossLaterContract() throws Exception {
        UUID instanceId = createInstance();
        PlayerContext player = playerInInstance("NoHuntKill", instanceId);
        ResourceEntityKillClaim claim = authoritativeKill(player, instanceId, "zombie.01");

        BountyKillProgressResult first = progress.recordManagedKill(
                claim.operationId(),
                player.playerId(),
                SOURCE_DEFINITION,
                FAMILY,
                1,
                "bounty.managed_kill"
        );
        assertFalse(first.applied());
        assertEquals(1L, bridgeCount(claim.operationId()));
        assertEquals(1L, processedCount(BountyKillProgressRepository.progressOperationId(claim.operationId())));

        UUID laterContract = insertActiveContract(player.playerId(), 3);
        BountyKillProgressResult replay = progress.recordManagedKill(
                claim.operationId(),
                player.playerId(),
                SOURCE_DEFINITION,
                FAMILY,
                1,
                "bounty.managed_kill"
        );

        assertEquals(first, replay);
        assertEquals(0, readProgress(laterContract));
        assertEquals(0L, readVersion(laterContract));
    }

    @Test
    void distinctManagedKillsAdvanceExactlyOnceAndUnlockSummon() throws Exception {
        UUID instanceId = createInstance();
        PlayerContext player = playerInInstance("HuntKill", instanceId);
        UUID contractId = insertActiveContract(player.playerId(), 2);

        ResourceEntityKillClaim firstClaim = authoritativeKill(player, instanceId, "zombie.01");
        BountyKillProgressResult first = progress.recordManagedKill(
                firstClaim.operationId(), player.playerId(), SOURCE_DEFINITION, FAMILY, 1, "bounty.managed_kill"
        );
        BountyKillProgressResult retry = progress.recordManagedKill(
                firstClaim.operationId(), player.playerId(), SOURCE_DEFINITION, FAMILY, 1, "bounty.managed_kill"
        );

        assertEquals(first, retry);
        assertTrue(first.applied());
        assertEquals(BountyContractStatus.ACTIVE_HUNT, first.contract().status());
        assertEquals(1, first.contract().eligibleKillProgress());
        assertEquals(1L, first.contract().stateVersion());

        ResourceEntityKillClaim secondClaim = authoritativeKill(player, instanceId, "zombie.02");
        BountyKillProgressResult second = progress.recordManagedKill(
                secondClaim.operationId(), player.playerId(), SOURCE_DEFINITION, FAMILY, 1, "bounty.managed_kill"
        );
        assertTrue(second.applied());
        assertEquals(BountyContractStatus.SUMMON_READY, second.contract().status());
        assertEquals(2, second.contract().eligibleKillProgress());
        assertEquals(1, second.contract().summonAuthorizationsRemaining());
        assertEquals(2L, second.contract().stateVersion());
        assertEquals(2, readProgress(contractId));
        assertEquals(2L, bridgeCountAll());
    }

    @Test
    void forgedPlayerOrSourceCannotClassifyAuthoritativeKill() throws Exception {
        UUID instanceId = createInstance();
        PlayerContext player = playerInInstance("TrustKill", instanceId);
        ResourceEntityKillClaim claim = authoritativeKill(player, instanceId, "zombie.01");
        UUID outsider = identities.ensurePlayer(UUID.randomUUID(), "TrustOther");

        assertThrows(
                BountyException.class,
                () -> progress.recordManagedKill(
                        claim.operationId(), outsider, SOURCE_DEFINITION, FAMILY, 1, "bounty.managed_kill"
                )
        );
        assertThrows(
                BountyException.class,
                () -> progress.recordManagedKill(
                        claim.operationId(), player.playerId(), "starter_pve.fake", FAMILY, 1, "bounty.managed_kill"
                )
        );
        assertEquals(0L, bridgeCountAll());
    }

    @Test
    void recoveryScanReturnsOnlyUnclassifiedEligibleEntityHarvests() throws Exception {
        UUID instanceId = createInstance();
        PlayerContext player = playerInInstance("RecoverKill", instanceId);
        ResourceEntityKillClaim first = authoritativeKill(player, instanceId, "zombie.01");
        ResourceEntityKillClaim second = authoritativeKill(player, instanceId, "zombie.02");

        progress.recordManagedKill(
                first.operationId(), player.playerId(), SOURCE_DEFINITION, FAMILY, 1, "bounty.managed_kill"
        );
        List<BountyManagedKillCandidate> pending = progress.listUnclassifiedManagedKills(
                List.of(SOURCE_DEFINITION),
                10
        );

        assertEquals(1, pending.size());
        assertEquals(second.operationId(), pending.getFirst().resourceKillOperationId());
        assertEquals(player.playerId(), pending.getFirst().playerId());
        assertEquals(SOURCE_DEFINITION, pending.getFirst().sourceDefinitionId());
    }

    private ResourceEntityKillClaim authoritativeKill(
            PlayerContext player,
            UUID instanceId,
            String sourceKey
    ) throws SQLException {
        ResourceSourceSnapshot source = sources.ensureSource(instanceId, sourceKey, SOURCE_DEFINITION);
        entitySpawns.ensureEntitySource(source.sourceId());
        ResourceEntitySpawnSnapshot pending = entitySpawns.reserveSpawn(
                source.sourceId(), Duration.ofSeconds(5)
        ).orElseThrow();
        UUID entityUuid = UUID.randomUUID();
        ResourceEntitySpawnSnapshot active = entitySpawns.confirmSpawn(
                pending.spawnId(), entityUuid, Duration.ofMinutes(5)
        );
        ResourceEntityKillClaim claim = entitySpawns.prepareKillClaim(active.spawnId(), entityUuid);
        gathering.harvestAndFulfill(
                claim.operationId(),
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                source.sourceId(),
                "resource.entity_kill"
        );
        return claim;
    }

    private UUID createInstance() throws SQLException {
        UUID instanceId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement backend = connection.prepareStatement("""
                    INSERT INTO backends(backend_id, status)
                    VALUES ('paper-a', 'ONLINE')
                    ON CONFLICT (backend_id) DO NOTHING
                    """)) {
                backend.executeUpdate();
            }
            try (PreparedStatement instance = connection.prepareStatement("""
                    INSERT INTO zone_instances(
                        instance_id, zone_id, template_version, backend_id, status,
                        player_count, soft_capacity, hard_capacity
                    ) VALUES (?, ?, ?, 'paper-a', 'ACTIVE', 0, 20, 30)
                    """)) {
                instance.setObject(1, instanceId);
                instance.setString(2, ZONE);
                instance.setString(3, TEMPLATE);
                instance.executeUpdate();
            }
        }
        return instanceId;
    }

    private PlayerContext playerInInstance(String name, UUID instanceId) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-a", instanceId, SESSION_LEASE);
        return new PlayerContext(playerId, session);
    }

    private UUID insertActiveContract(UUID playerId, int requiredKills) throws SQLException {
        UUID contractId = UUID.randomUUID();
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
        return contractId;
    }

    private int readProgress(UUID contractId) throws SQLException {
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

    private long readVersion(UUID contractId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state_version FROM bounty_contracts WHERE contract_id = ?"
             )) {
            statement.setObject(1, contractId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
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
