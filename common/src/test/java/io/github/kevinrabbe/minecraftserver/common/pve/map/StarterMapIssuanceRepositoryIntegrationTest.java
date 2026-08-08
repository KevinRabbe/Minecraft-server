package io.github.kevinrabbe.minecraftserver.common.pve.map;

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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class StarterMapIssuanceRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");
    private static final Duration SESSION_LEASE = Duration.ofSeconds(30);
    private static final String ZONE = "city";
    private static final String TEMPLATE = "city-dev-v1";
    private static final String SOURCE_DEFINITION = "starter_combat.ruinbound_champion";
    private static final String DROP = "material.test_scrap";
    private static final String MAP = "map.challenge";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private ResourceSourceRepository sources;
    private ResourceEntitySpawnRepository entitySpawns;
    private ResourceGatheringService gathering;
    private StarterMapIssuanceRepository issuances;
    private MapPendingDeliveryAuthority pendingMaps;

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

        ItemCatalog items = new ItemCatalog(List.of(
                new ItemDefinition(DROP, "FLINT", "Test Scrap", 64, ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY),
                new ItemDefinition(MAP, "MAP", "Challenge Map", 1, ItemCategory.PROGRESSION, ItemIdentityKind.INDIVIDUAL)
        ));
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(curve(new SkillId("combat"))));
        ResourceSourceCatalog sourceCatalog = new ResourceSourceCatalog(
                List.of(new ResourceSourceDefinition(
                        SOURCE_DEFINITION,
                        ZONE,
                        TEMPLATE,
                        DROP,
                        1,
                        null,
                        0,
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
        issuances = new StarterMapIssuanceRepository(dataSource);
        pendingMaps = new MapPendingDeliveryAuthority(dataSource, items, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        starter_map_issuances,
                        resource_entity_kill_claims,
                        resource_entity_spawns,
                        resource_entity_sources,
                        resource_harvest_fulfillments,
                        resource_harvests,
                        resource_sources,
                        skill_xp_awards,
                        pending_commodity_deliveries,
                        map_item_profiles,
                        pending_unique_deliveries,
                        item_provenance,
                        item_instances,
                        economic_ledger,
                        processed_operations,
                        historical_events,
                        world_eras,
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
        insertEra("founding", 0, NOW.minusSeconds(3600));
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void authoritativeKillCanBeIssuedAndClassifiedExactlyOnce() throws Exception {
        UUID instanceId = createInstance();
        PlayerContext player = playerInInstance("StarterMapOwner", instanceId);
        ResourceEntityKillClaim kill = authoritativeKill(player, instanceId, "champion.01");

        List<StarterMapIssuanceCandidate> unissued = issuances.listUnissued(SOURCE_DEFINITION, 10);
        assertEquals(1, unissued.size());
        assertEquals(kill.operationId(), unissued.getFirst().resourceKillOperationId());
        assertEquals(player.playerId(), unissued.getFirst().playerId());

        UUID issueOperation = issueOperation(kill.operationId());
        MapRunDefinition definition = starterDefinition(kill.operationId());
        MapPendingDeliveryResult pending = pendingMaps.createPending(
                issueOperation,
                MAP,
                player.playerId(),
                definition,
                "map.starter_elite"
        );
        issuances.recordIssued(
                kill.operationId(),
                SOURCE_DEFINITION,
                issueOperation,
                player.playerId(),
                pending
        );

        assertTrue(issuances.listUnissued(SOURCE_DEFINITION, 10).isEmpty());
        assertEquals(1L, issuanceCount(kill.operationId()));
        assertEquals(definition, pending.mapProfile().runDefinition());

        MapPendingDeliveryResult retryPending = pendingMaps.createPending(
                issueOperation,
                MAP,
                player.playerId(),
                definition,
                "map.starter_elite"
        );
        assertEquals(pending, retryPending);
        issuances.recordIssued(
                kill.operationId(),
                SOURCE_DEFINITION,
                issueOperation,
                player.playerId(),
                retryPending
        );
        assertEquals(1L, issuanceCount(kill.operationId()));
    }

    @Test
    void mapCreatedBeforeBridgeRecordRemainsRecoverableWithoutDuplicateItem() throws Exception {
        UUID instanceId = createInstance();
        PlayerContext player = playerInInstance("CrashWindow", instanceId);
        ResourceEntityKillClaim kill = authoritativeKill(player, instanceId, "champion.01");
        UUID issueOperation = issueOperation(kill.operationId());
        MapRunDefinition definition = starterDefinition(kill.operationId());

        MapPendingDeliveryResult first = pendingMaps.createPending(
                issueOperation,
                MAP,
                player.playerId(),
                definition,
                "map.starter_elite"
        );
        assertEquals(1, issuances.listUnissued(SOURCE_DEFINITION, 10).size());

        MapPendingDeliveryResult recovered = pendingMaps.createPending(
                issueOperation,
                MAP,
                player.playerId(),
                definition,
                "map.starter_elite"
        );
        assertEquals(first, recovered);
        issuances.recordIssued(
                kill.operationId(),
                SOURCE_DEFINITION,
                issueOperation,
                player.playerId(),
                recovered
        );

        assertTrue(issuances.listUnissued(SOURCE_DEFINITION, 10).isEmpty());
        assertEquals(1L, pendingItemCount(issueOperation));
        assertEquals(1L, issuanceCount(kill.operationId()));
    }

    @Test
    void forgedKillIdentityCannotBeBoundToStarterMap() throws Exception {
        UUID instanceId = createInstance();
        PlayerContext player = playerInInstance("TrustBoundary", instanceId);
        ResourceEntityKillClaim kill = authoritativeKill(player, instanceId, "champion.01");
        UUID outsider = identities.ensurePlayer(UUID.randomUUID(), "OtherPlayer");
        UUID issueOperation = issueOperation(kill.operationId());
        MapRunDefinition definition = starterDefinition(kill.operationId());
        MapPendingDeliveryResult outsiderPending = pendingMaps.createPending(
                issueOperation,
                MAP,
                outsider,
                definition,
                "map.starter_elite"
        );

        assertThrows(
                MapAuthorityException.class,
                () -> issuances.recordIssued(
                        kill.operationId(),
                        SOURCE_DEFINITION,
                        issueOperation,
                        outsider,
                        outsiderPending
                )
        );
        assertEquals(0L, issuanceCount(kill.operationId()));
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
                "paper-city",
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
                    VALUES ('paper-city', 'ONLINE')
                    ON CONFLICT (backend_id) DO NOTHING
                    """)) {
                backend.executeUpdate();
            }
            try (PreparedStatement instance = connection.prepareStatement("""
                    INSERT INTO zone_instances(
                        instance_id, zone_id, template_version, backend_id, status,
                        player_count, soft_capacity, hard_capacity
                    ) VALUES (?, ?, ?, 'paper-city', 'ACTIVE', 0, 20, 30)
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
        SessionLease session = sessions.openSession(playerId, "paper-city", instanceId, SESSION_LEASE);
        return new PlayerContext(playerId, session);
    }

    private static MapRunDefinition starterDefinition(UUID killOperationId) {
        long seed = killOperationId.getMostSignificantBits() ^ killOperationId.getLeastSignificantBits();
        return new MapRunDefinition(
                new MapDifficulty(1),
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

    private static UUID issueOperation(UUID killOperationId) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:starter-map:" + killOperationId).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private long issuanceCount(UUID killOperationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM starter_map_issuances WHERE resource_kill_operation_id = ?
                     """)) {
            statement.setObject(1, killOperationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long pendingItemCount(UUID issueOperationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM pending_unique_deliveries WHERE issue_operation_id = ?
                     """)) {
            statement.setObject(1, issueOperationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private void insertEra(String eraId, int sequence, Instant startedAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO world_eras(era_id, sequence_no, started_at)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setString(1, eraId);
            statement.setInt(2, sequence);
            statement.setTimestamp(3, Timestamp.from(startedAt));
            statement.executeUpdate();
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
