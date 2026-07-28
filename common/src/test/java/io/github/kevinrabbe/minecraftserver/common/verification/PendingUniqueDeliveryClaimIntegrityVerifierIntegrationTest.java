package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemLocation;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryClaimResult;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryIssueResult;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryRepository;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PendingUniqueDeliveryClaimIntegrityVerifierIntegrationTest {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String DEFINITION = "equipment.integrity_delivery_sword";
    private static final String BACKEND = "paper-a";
    private static final String CLAIM_REASON = "test.unique_delivery_claim";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private PendingUniqueDeliveryRepository deliveries;
    private UniqueItemAuthorityRepository items;
    private PendingUniqueDeliveryClaimIntegrityVerifier verifier;
    private EconomyIntegrityVerifier economyVerifier;

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
        ItemCatalog catalog = new ItemCatalog(List.of(new ItemDefinition(
                DEFINITION,
                "IRON_SWORD",
                "Integrity Delivery Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL
        )));
        deliveries = new PendingUniqueDeliveryRepository(dataSource, catalog);
        items = new UniqueItemAuthorityRepository(dataSource, catalog);
        verifier = new PendingUniqueDeliveryClaimIntegrityVerifier(dataSource);
        economyVerifier = new EconomyIntegrityVerifier(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        truncateAuthority();
    }

    @AfterEach
    void cleanDatabase() throws SQLException {
        truncateAuthority();
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) database.close();
    }

    @Test
    void realClaimReconcilesCleanly() throws Exception {
        ClaimContext claim = claimedDelivery("UniqueClaim");

        assertEquals(1L, claim.result().itemStateVersion());
        assertEquals(1L, claim.result().playerStateVersion());
        assertTrue(verifier.verify(100).isEmpty());
        assertTrue(economyVerifier.verify(100).isEmpty());
    }

    @Test
    void laterItemMovementDoesNotInvalidateHistoricalClaim() throws Exception {
        ClaimContext claim = claimedDelivery("UniqueMove");

        items.move(
                UUID.randomUUID(),
                claim.issued().itemInstanceId(),
                claim.result().itemStateVersion(),
                ItemLocation.playerInventory(claim.playerId()),
                ItemLocation.quarantine(),
                "test.after_unique_claim",
                claim.playerId()
        );

        assertTrue(verifier.verify(100).isEmpty());
        assertTrue(economyVerifier.verify(100).isEmpty());
    }

    @Test
    void laterPlayerStateVersionDoesNotInvalidateHistoricalClaim() throws Exception {
        ClaimContext claim = claimedDelivery("UniqueAdvance");
        SessionLease refreshed = sessions.heartbeat(claim.session().sessionId(), BACKEND, LEASE);
        long advancedVersion = states.commit(
                refreshed.sessionId(),
                BACKEND,
                refreshed.stateVersion(),
                "city",
                "after-claim",
                new byte[]{9, 8, 7}
        );

        assertEquals(claim.result().playerStateVersion() + 1, advancedVersion);
        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void claimedDeliveryWithoutProcessedClaimIsReported() throws Exception {
        ClaimContext claim = claimedDelivery("UniMissingOp");

        withTriggerDisabled("processed_operations", "processed_operations_append_only", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM processed_operations WHERE operation_id = ?")) {
                statement.setObject(1, claim.claimOperationId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssueOnly(claim.issued().deliveryId().toString());
    }

    @Test
    void missingDeliveredProvenanceIsReported() throws Exception {
        ClaimContext claim = claimedDelivery("UniNoProv");

        withTriggerDisabled("item_provenance", "item_provenance_append_only", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM item_provenance
                    WHERE item_instance_id = ? AND operation_id = ?
                    """)) {
                statement.setObject(1, claim.issued().itemInstanceId());
                statement.setObject(2, claim.claimOperationId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssueOnly(claim.issued().deliveryId().toString());
    }

    @Test
    void malformedProcessedItemVersionIsReportedInsteadOfCrashing() throws Exception {
        ClaimContext claim = claimedDelivery("UniBadVer");

        withTriggerDisabled("processed_operations", "processed_operations_append_only", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE processed_operations
                    SET result = jsonb_set(result, '{item_state_version}', '"not-a-number"'::jsonb, false)
                    WHERE operation_id = ?
                    """)) {
                statement.setObject(1, claim.claimOperationId());
                assertEquals(1, statement.executeUpdate());
            }
        });

        assertIssueOnly(claim.issued().deliveryId().toString());
    }

    @Test
    void orphanProcessedClaimIsReported() throws Exception {
        UUID operationId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO processed_operations(operation_id, operation_type, result)
                     VALUES (?, 'PENDING_UNIQUE_DELIVERY_CLAIM', '{}'::jsonb)
                     """)) {
            statement.setObject(1, operationId);
            assertEquals(1, statement.executeUpdate());
        }

        assertIssueOnly(operationId.toString());
    }

    private ClaimContext claimedDelivery(String name) throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        PendingUniqueDeliveryIssueResult issued = deliveries.issueNewIndividual(
                UUID.randomUUID(),
                DEFINITION,
                playerId,
                "test.unique_delivery_issue",
                playerId
        );
        SessionLease session = sessions.openSession(playerId, BACKEND, null, LEASE);
        UUID claimOperationId = UUID.randomUUID();
        PendingUniqueDeliveryClaimResult result = deliveries.claimToPlayerState(
                claimOperationId,
                issued.deliveryId(),
                session.sessionId(),
                BACKEND,
                session.stateVersion(),
                "city",
                "unique-delivery",
                new byte[]{1, 2, 3, 4},
                CLAIM_REASON
        );
        return new ClaimContext(playerId, session, issued, claimOperationId, result);
    }

    private void withTriggerDisabled(
            String table,
            String trigger,
            SqlMutation mutation
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement control = connection.createStatement()) {
            control.execute("ALTER TABLE " + table + " DISABLE TRIGGER " + trigger);
            try {
                mutation.apply(connection);
            } finally {
                control.execute("ALTER TABLE " + table + " ENABLE TRIGGER " + trigger);
            }
        }
    }

    private void assertIssueOnly(String expectedSubject) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("PENDING_UNIQUE_CLAIM_EVIDENCE_MISMATCH", issue.code());
        assertEquals(expectedSubject, issue.subjectId());
    }

    private void truncateAuthority() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE processed_operations, players RESTART IDENTITY CASCADE");
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlMutation {
        void apply(Connection connection) throws SQLException;
    }

    private record ClaimContext(
            UUID playerId,
            SessionLease session,
            PendingUniqueDeliveryIssueResult issued,
            UUID claimOperationId,
            PendingUniqueDeliveryClaimResult result
    ) {
    }
}
