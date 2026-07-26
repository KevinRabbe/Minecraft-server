package io.github.kevinrabbe.minecraftserver.common.pve.map;

import io.github.kevinrabbe.minecraftserver.common.control.BackendRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceRegistry;
import io.github.kevinrabbe.minecraftserver.common.control.ZoneInstanceStatus;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MapCompletedEncounterRecoveryRepositoryIntegrationTest {
    private static final String MAP_ID = "map.complete_recovery";
    private static final String SOURCE_BACKEND = "paper-source";
    private static final String TARGET_BACKEND = "paper-map";
    private static final String MAP_ZONE = "map_encounter";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private BackendRegistry backends;
    private ZoneInstanceRegistry instances;
    private MapAuthorityRepository maps;
    private MapPlayerStateOpenRepository statefulMaps;
    private MapEncounterReservationRepository reservations;
    private MapEncounterReservationReleaseRepository releases;
    private MapCompletedEncounterRecoveryRepository recovery;

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
        backends = new BackendRegistry(dataSource);
        instances = new ZoneInstanceRegistry(dataSource);
        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                MAP_ID,
                "MAP",
                "Recovery Map",
                1,
                ItemCategory.PROGRESSION,
                ItemIdentityKind.INDIVIDUAL
        )));
        maps = new MapAuthorityRepository(dataSource, catalog);
        statefulMaps = new MapPlayerStateOpenRepository(
                dataSource,
                catalog,
                (playerId, itemId, itemVersion, currentPayload, nextPayload) -> { }
        );
        reservations = new MapEncounterReservationRepository(dataSource, Duration.ofSeconds(30));
        releases = new MapEncounterReservationReleaseRepository(dataSource);
        recovery = new MapCompletedEncounterRecoveryRepository(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        map_reward_grants,
                        map_reward_settlements,
                        map_encounter_handoffs,
                        map_encounter_reservations,
                        map_open_player_state_evidence,
                        map_clears,
                        map_run_participants,
                        map_runs,
                        map_item_profiles,
                        item_provenance,
                        item_instances,
                        processed_operations,
                        economic_ledger,
                        transfer_tickets,
                        player_sessions,
                        zone_instances,
                        backends,
                        player_state,
                        player_names,
                        wallets,
                        historical_events,
                        world_eras,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
        backends.registerOnline(SOURCE_BACKEND, 1);
        backends.registerOnline(TARGET_BACKEND, 0);
        insertEra();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void completedBoundRunRemainsRecoverableUntilSlotRelease() throws Exception {
        UUID targetInstance = UUID.randomUUID();
        instances.registerStarting(targetInstance, MAP_ZONE, "v1", TARGET_BACKEND, 1, 1);
        instances.heartbeat(targetInstance, ZoneInstanceStatus.ACTIVE, 0);
        backends.heartbeat(TARGET_BACKEND, 0);

        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "MapComplete");
        SessionLease session = sessions.openSession(playerId, SOURCE_BACKEND, null, Duration.ofMinutes(5));
        MapItemProfile map = maps.issueMap(
                UUID.randomUUID(),
                MAP_ID,
                playerId,
                definition(),
                "map.issue"
        );
        UUID openOperation = UUID.randomUUID();
        MapEncounterReservationSnapshot reservation = reservations.reserve(
                openOperation,
                playerId,
                map.itemInstanceId(),
                MAP_ZONE,
                "v1",
                Duration.ofSeconds(30)
        );
        MapPlayerStateOpenResult opened = statefulMaps.openMap(
                openOperation,
                map.itemInstanceId(),
                session.sessionId(),
                SOURCE_BACKEND,
                session.stateVersion(),
                0,
                "starter_pve",
                "portal",
                new byte[]{8, 4},
                "map.open"
        );
        MapRunSnapshot created = maps.loadRun(opened.runId());
        maps.startRun(
                UUID.randomUUID(),
                opened.runId(),
                created.stateVersion(),
                List.of(playerId),
                "map.start"
        );
        MapRunSnapshot active = maps.loadRun(opened.runId());
        maps.completeRun(
                UUID.randomUUID(),
                opened.runId(),
                active.stateVersion(),
                1234,
                "map.complete"
        );
        MapRunSnapshot completed = maps.loadRun(opened.runId());

        List<MapCompletedEncounterCandidate> candidates = recovery.listRecoverable(10);
        assertEquals(1, candidates.size());
        assertEquals(opened.runId(), candidates.getFirst().runId());
        assertEquals(reservation.reservationId(), candidates.getFirst().reservationId());
        assertEquals(completed.stateVersion(), candidates.getFirst().runStateVersion());

        releases.releaseTerminalRun(reservation.reservationId(), opened.runId());
        assertTrue(recovery.listRecoverable(10).isEmpty());
    }

    private MapRunDefinition definition() {
        return new MapRunDefinition(
                new MapDifficulty(1),
                "forest",
                "spider",
                "extermination",
                List.of(),
                77L,
                1,
                1,
                "founding"
        );
    }

    private void insertEra() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO world_eras(era_id, sequence_no, started_at)
                     VALUES ('founding', 0, ?)
                     """)) {
            statement.setTimestamp(1, Timestamp.from(Instant.parse("2026-07-25T12:00:00Z")));
            statement.executeUpdate();
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
