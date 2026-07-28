package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageCatalog;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageDefinition;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.SalvageResult;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityResult;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SalvageIntegrityVerifierIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String SWORD = "verify.salvage_sword";
    private static final String SCRAP = "verify.salvage_scrap";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private UniqueItemAuthorityRepository items;
    private SalvageCatalog salvageCatalog;
    private SalvageIntegrityVerifier verifier;

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
        ItemCatalog catalog = new ItemCatalog(List.of(
                new ItemDefinition(
                        SWORD, "IRON_SWORD", "Verifier Sword", 1,
                        ItemCategory.EQUIPMENT, ItemIdentityKind.INDIVIDUAL
                ),
                new ItemDefinition(
                        SCRAP, "IRON_NUGGET", "Verifier Scrap", 64,
                        ItemCategory.MATERIALS, ItemIdentityKind.COMMODITY
                )
        ));
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        salvageCatalog = new SalvageCatalog(
                List.of(new SalvageDefinition(SWORD, 250, Map.of(SCRAP, 3L))),
                catalog
        );
        verifier = new SalvageIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        salvage_records,
                        pending_commodity_deliveries,
                        item_provenance,
                        item_instances,
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

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void healthySalvageReconcilesExactStateLedgerAndDeliveryEvidence() throws Exception {
        SalvageFixture fixture = salvage("SalvageVerify");

        assertTrue(verifier.verify(100).isEmpty());
        assertEquals(250L, fixture.result().coinReturnMinor());
        assertEquals(1, fixture.result().commodityReturns().size());
    }

    @Test
    void laterLegitimatePlayerStateAdvanceKeepsHistoricalSalvageValid() throws Exception {
        SalvageFixture fixture = salvage("SalvageLater");

        states.commit(
                fixture.player().session().sessionId(),
                "paper-a",
                fixture.result().playerStateVersion(),
                "city",
                "after_salvage",
                new byte[]{7, 7}
        );

        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void processedSalvageResultDriftIsReported() throws Exception {
        SalvageFixture fixture = salvage("SalvageOp");

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE processed_operations
                    SET result = jsonb_set(result, '{result,coin_return_minor}', '251'::jsonb)
                    WHERE operation_id = ?
                    """)) {
                statement.setObject(1, fixture.result().operationId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("SALVAGE_OPERATION_EVIDENCE_MISMATCH", fixture.result().salvageId().toString());
    }

    @Test
    void malformedProcessedStateVersionIsReportedWithoutVerifierFailure() throws Exception {
        SalvageFixture fixture = salvage("SalvageState");

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE processed_operations
                    SET result = jsonb_set(result, '{result,player_state_version}', '"broken"'::jsonb)
                    WHERE operation_id = ?
                    """)) {
                statement.setObject(1, fixture.result().operationId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("SALVAGE_OPERATION_EVIDENCE_MISMATCH", fixture.result().salvageId().toString());
    }

    @Test
    void salvageLedgerDriftIsReported() throws Exception {
        SalvageFixture fixture = salvage("SalvageLedger");

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE economic_ledger
                    SET amount = amount + 1
                    WHERE operation_id = ?
                      AND asset_type = 'COMMODITY'
                    """)) {
                statement.setObject(1, fixture.result().operationId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("SALVAGE_LEDGER_EVIDENCE_MISMATCH", fixture.result().salvageId().toString());
    }

    @Test
    void salvageCommodityDeliveryDriftIsReported() throws Exception {
        SalvageFixture fixture = salvage("SalvageDeliv");
        UUID deliveryId = fixture.result().commodityReturns().getFirst().deliveryId();

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE pending_commodity_deliveries
                    SET quantity = quantity + 1
                    WHERE delivery_id = ?
                    """)) {
                statement.setObject(1, deliveryId);
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("SALVAGE_RETURN_DELIVERY_MISMATCH", fixture.result().salvageId().toString());
    }

    @Test
    void salvageCommoditySourceOperationDriftIsReported() throws Exception {
        SalvageFixture fixture = salvage("SalvageSource");
        UUID deliveryId = fixture.result().commodityReturns().getFirst().deliveryId();

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE pending_commodity_deliveries
                    SET source_operation_id = ?
                    WHERE delivery_id = ?
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, deliveryId);
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("SALVAGE_RETURN_DELIVERY_MISMATCH", fixture.result().salvageId().toString());
    }

    @Test
    void orphanProcessedSalvageIsReportedByOperationId() throws Exception {
        SalvageFixture fixture = salvage("SalvageOrphan");

        withReplicationTriggersDisabled(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM salvage_records WHERE salvage_id = ?")) {
                statement.setObject(1, fixture.result().salvageId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssue("SALVAGE_OPERATION_EVIDENCE_MISMATCH", fixture.result().operationId().toString());
    }

    private SalvageFixture salvage(String name) throws Exception {
        PlayerContext player = playerWithSession(name, new byte[]{9, 8});
        UniqueItemAuthorityResult item = items.createForPlayer(
                UUID.randomUUID(), SWORD, player.playerId(), "test.item", player.playerId()
        );
        SalvageRepository salvage = new SalvageRepository(
                dataSource,
                salvageCatalog,
                (playerId, itemId, current, next) -> {
                    if (!player.playerId().equals(playerId) || !item.itemInstanceId().equals(itemId)) {
                        throw new AssertionError("salvage validator received wrong authority identity");
                    }
                }
        );
        SalvageResult result = salvage.salvage(
                UUID.randomUUID(),
                player.session().sessionId(),
                "paper-a",
                player.session().stateVersion(),
                item.itemInstanceId(),
                item.stateVersion(),
                "city",
                "salvage",
                new byte[]{8},
                "salvage.item"
        );
        return new SalvageFixture(player, result);
    }

    private PlayerContext playerWithSession(String name, byte[] payload) throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, "paper-a", null, LEASE);
        long version = states.commit(
                session.sessionId(), "paper-a", session.stateVersion(), "city", "salvage", payload
        );
        SessionLease refreshed = sessions.heartbeat(session.sessionId(), "paper-a", LEASE);
        assertEquals(version, refreshed.stateVersion());
        return new PlayerContext(playerId, refreshed);
    }

    private void assertIssue(String code, String subjectId) throws SQLException {
        assertTrue(verifier.verify(100).stream().anyMatch(issue ->
                issue.code().equals(code) && issue.subjectId().equals(subjectId)));
    }

    private void withReplicationTriggersDisabled(SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record PlayerContext(UUID playerId, SessionLease session) { }

    private record SalvageFixture(PlayerContext player, SalvageResult result) { }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }
}
