package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDefinitionResolver;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliveryAuthority;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliveryClaimResult;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliveryRepository;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityDeliverySnapshot;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityStateMutator;
import io.github.kevinrabbe.minecraftserver.common.persistence.Database;
import io.github.kevinrabbe.minecraftserver.common.persistence.DatabaseConfig;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerIdentityRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerSessionRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateSnapshot;
import io.github.kevinrabbe.minecraftserver.common.session.SessionLease;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
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
class CommodityDeliveryIntegrityVerifierIntegrationTest {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String COMMODITY = "integrity.delivery_iron";
    private static final String BACKEND = "paper-a";
    private static final String CLAIM_REASON = "test.commodity_delivery_claim";

    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;
    private PlayerSessionRepository sessions;
    private PlayerStateRepository states;
    private CommodityDeliveryAuthority deliveryAuthority;
    private CommodityDeliveryRepository deliveries;
    private CommodityDeliveryIntegrityVerifier verifier;
    private CommodityStateMutator mutator;

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
        mutator = new LongPayloadCommodityMutator();
        CommodityDefinitionResolver definitions = definitionId -> {
            if (!COMMODITY.equals(definitionId)) {
                throw new IllegalArgumentException("Unknown test commodity: " + definitionId);
            }
            return COMMODITY;
        };
        deliveryAuthority = new CommodityDeliveryAuthority(dataSource, definitions);
        deliveries = new CommodityDeliveryRepository(dataSource, mutator);
        verifier = new CommodityDeliveryIntegrityVerifier(dataSource);
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
        ClaimContext claim = claimedDelivery("DeliveryClean", 7L);

        assertEquals(1L, claim.result().playerStateVersion());
        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void laterPlayerStateVersionDoesNotInvalidateHistoricalClaim() throws Exception {
        ClaimContext claim = claimedDelivery("DeliveryAdvance", 5L);
        SessionLease refreshed = sessions.heartbeat(claim.session().sessionId(), BACKEND, LEASE);
        long advancedVersion = states.commit(
                refreshed.sessionId(),
                BACKEND,
                refreshed.stateVersion(),
                "city",
                "after-claim",
                encode(6L)
        );

        assertEquals(claim.result().playerStateVersion() + 1, advancedVersion);
        assertTrue(verifier.verify(100).isEmpty());
    }

    @Test
    void claimedDeliveryWithoutProcessedClaimIsReported() throws Exception {
        ClaimContext claim = claimedDelivery("DeliveryMissingOp", 3L);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM processed_operations WHERE operation_id = ?")) {
            statement.setObject(1, claim.claimOperationId());
            assertEquals(1, statement.executeUpdate());
        }

        assertIssueOnly(claim.delivery().deliveryId().toString());
    }

    @Test
    void processedClaimIdentityDriftIsReported() throws Exception {
        ClaimContext claim = claimedDelivery("DeliveryDrift", 9L);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE processed_operations
                     SET result = jsonb_set(
                         result,
                         '{quantity}',
                         to_jsonb(((result ->> 'quantity')::BIGINT + 1)),
                         false
                     )
                     WHERE operation_id = ?
                     """)) {
            statement.setObject(1, claim.claimOperationId());
            assertEquals(1, statement.executeUpdate());
        }

        assertIssueOnly(claim.delivery().deliveryId().toString());
    }

    @Test
    void malformedProcessedStateVersionIsReportedInsteadOfCrashing() throws Exception {
        ClaimContext claim = claimedDelivery("DeliveryBadVer", 4L);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE processed_operations
                     SET result = jsonb_set(result, '{player_state_version}', '"not-a-number"'::jsonb, false)
                     WHERE operation_id = ?
                     """)) {
            statement.setObject(1, claim.claimOperationId());
            assertEquals(1, statement.executeUpdate());
        }

        assertIssueOnly(claim.delivery().deliveryId().toString());
    }

    @Test
    void playerStateBehindCommittedClaimVersionIsReported() throws Exception {
        ClaimContext claim = claimedDelivery("DeliveryStateBack", 6L);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_state
                     SET state_version = ?
                     WHERE player_id = ?
                     """)) {
            statement.setLong(1, claim.result().playerStateVersion() - 1);
            statement.setObject(2, claim.playerId());
            assertEquals(1, statement.executeUpdate());
        }

        assertIssueOnly(claim.delivery().deliveryId().toString());
    }

    @Test
    void orphanProcessedClaimIsReported() throws Exception {
        UUID operationId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO processed_operations(operation_id, operation_type, result)
                     VALUES (?, 'COMMODITY_DELIVERY_CLAIM', '{}'::jsonb)
                     """)) {
            statement.setObject(1, operationId);
            assertEquals(1, statement.executeUpdate());
        }

        assertIssueOnly(operationId.toString());
    }

    private ClaimContext claimedDelivery(String name, long quantity) throws Exception {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), name);
        SessionLease session = sessions.openSession(playerId, BACKEND, null, LEASE);
        UUID sourceOperationId = UUID.randomUUID();
        CommodityDeliverySnapshot delivery = deliveryAuthority.createPending(
                sourceOperationId,
                playerId,
                COMMODITY,
                quantity
        );
        PlayerStateSnapshot currentState = states.load(playerId);
        byte[] nextPayload = mutator.add(
                playerId,
                COMMODITY,
                quantity,
                currentState.statePayload()
        );
        UUID claimOperationId = UUID.randomUUID();
        CommodityDeliveryClaimResult result = deliveries.claim(
                claimOperationId,
                delivery.deliveryId(),
                session.sessionId(),
                BACKEND,
                session.stateVersion(),
                "city",
                "commodity-delivery",
                nextPayload,
                CLAIM_REASON
        );
        return new ClaimContext(playerId, session, delivery, claimOperationId, result);
    }

    private void assertIssueOnly(String expectedSubject) throws SQLException {
        List<IntegrityIssue> issues = verifier.verify(100);
        assertEquals(1, issues.size(), () -> "unexpected issues: " + issues);
        IntegrityIssue issue = issues.getFirst();
        assertEquals(IntegritySeverity.CRITICAL, issue.severity());
        assertEquals("COMMODITY_DELIVERY_CLAIM_EVIDENCE_MISMATCH", issue.code());
        assertEquals(expectedSubject, issue.subjectId());
    }

    private void truncateAuthority() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE processed_operations, players RESTART IDENTITY CASCADE");
        }
    }

    private static byte[] encode(long quantity) {
        return ByteBuffer.allocate(Long.BYTES).putLong(quantity).array();
    }

    private static long decode(byte[] payload) {
        if (payload == null) return 0L;
        if (payload.length != Long.BYTES) {
            throw new IllegalArgumentException("Test payload must contain one long");
        }
        return ByteBuffer.wrap(payload).getLong();
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private record ClaimContext(
            UUID playerId,
            SessionLease session,
            CommodityDeliverySnapshot delivery,
            UUID claimOperationId,
            CommodityDeliveryClaimResult result
    ) {
    }

    private static final class LongPayloadCommodityMutator implements CommodityStateMutator {
        @Override
        public byte[] remove(
                UUID playerId,
                String commodityDefinitionId,
                long quantity,
                byte[] currentStatePayload
        ) {
            long current = decode(currentStatePayload);
            if (quantity <= 0 || current < quantity) {
                throw new IllegalArgumentException("Invalid test commodity removal");
            }
            return encode(current - quantity);
        }

        @Override
        public byte[] add(
                UUID playerId,
                String commodityDefinitionId,
                long quantity,
                byte[] currentStatePayload
        ) {
            if (quantity <= 0) {
                throw new IllegalArgumentException("Invalid test commodity addition");
            }
            return encode(Math.addExact(decode(currentStatePayload), quantity));
        }
    }
}
