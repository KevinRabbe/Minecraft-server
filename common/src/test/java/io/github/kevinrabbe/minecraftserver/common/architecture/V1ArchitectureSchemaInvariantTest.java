package io.github.kevinrabbe.minecraftserver.common.architecture;

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
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class V1ArchitectureSchemaInvariantTest {
    private Database database;
    private DataSource dataSource;
    private PlayerIdentityRepository identities;

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
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    TRUNCATE TABLE
                        historical_events,
                        expansion_ballots,
                        expansion_vote_candidates,
                        expansion_votes,
                        feature_states,
                        world_eras,
                        players
                    RESTART IDENTITY CASCADE
                    """);
        }
    }

    @AfterAll
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void newPlayerAutomaticallyGetsProtectedBankAccount() throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "BankOwner");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT balance_minor, tier, state_version
                     FROM bank_accounts
                     WHERE player_id = ?
                     """)) {
            statement.setObject(1, playerId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertEquals(0L, result.getLong("balance_minor"));
                assertEquals(0, result.getInt("tier"));
                assertEquals(0L, result.getLong("state_version"));
            }
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE bank_accounts
                     SET balance_minor = -1
                     WHERE player_id = ?
                     """)) {
            statement.setObject(1, playerId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void skillExperienceCannotBecomeNegative() throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "SkillOwner");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO player_skills(player_id, skill_id, experience)
                     VALUES (?, 'mining', -1)
                     """)) {
            statement.setObject(1, playerId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void onePlayerCannotHoldTwoActiveContractsForSameBountyFamily() throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "BountyOwner");
        insertBountyContract(playerId, UUID.randomUUID(), UUID.randomUUID());

        assertThrows(
                SQLException.class,
                () -> insertBountyContract(playerId, UUID.randomUUID(), UUID.randomUUID())
        );
    }

    @Test
    void expansionBallotMustReferenceCandidateFromExactCandidateSet() throws SQLException {
        UUID playerId = identities.ensurePlayer(UUID.randomUUID(), "Voter");
        UUID voteId = UUID.randomUUID();
        createVote(voteId);
        insertCandidate(voteId, "fishing", 0);
        insertCandidate(voteId, "logistics", 1);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO expansion_ballots(
                         vote_id, player_id, candidate_set_version, candidate_id
                     ) VALUES (?, ?, 1, 'unknown')
                     """)) {
            statement.setObject(1, voteId);
            statement.setObject(2, playerId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void resolvedExpansionWinnerMustBeARealCandidate() throws SQLException {
        UUID voteId = UUID.randomUUID();
        createVote(voteId);
        insertCandidate(voteId, "fishing", 0);
        insertCandidate(voteId, "logistics", 1);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE expansion_votes
                     SET status = 'RESOLVED',
                         winning_candidate_id = 'unknown',
                         resolution_operation_id = ?,
                         resolved_at = NOW()
                     WHERE vote_id = ?
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, voteId);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void historicalEventsAreAppendOnly() throws SQLException {
        UUID eventId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO historical_events(
                         event_id, event_type, source_kind, source_id, occurred_at
                     ) VALUES (?, 'WORLD_OPENED', 'SYSTEM', 'day0', ?)
                     """)) {
            insert.setObject(1, eventId);
            insert.setObject(2, java.sql.Timestamp.from(Instant.parse("2026-08-01T18:00:00Z")));
            insert.executeUpdate();
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE historical_events
                     SET source_id = 'rewritten'
                     WHERE event_id = ?
                     """)) {
            update.setObject(1, eventId);
            assertThrows(SQLException.class, update::executeUpdate);
        }
    }

    private void insertBountyContract(UUID playerId, UUID contractId, UUID feeOperationId) throws SQLException {
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
                         fee_operation_id
                     ) VALUES (?, ?, 'spider', 1, 'ACTIVE_HUNT', 0, 10, 0, ?)
                     """)) {
            statement.setObject(1, contractId);
            statement.setObject(2, playerId);
            statement.setObject(3, feeOperationId);
            statement.executeUpdate();
        }
    }

    private void createVote(UUID voteId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO expansion_votes(
                         vote_id, candidate_set_version, status, opens_at, closes_at
                     ) VALUES (?, 1, 'OPEN', NOW() - INTERVAL '1 hour', NOW() + INTERVAL '1 day')
                     """)) {
            statement.setObject(1, voteId);
            statement.executeUpdate();
        }
    }

    private void insertCandidate(UUID voteId, String candidateId, int ordinal) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO expansion_vote_candidates(
                         vote_id,
                         candidate_set_version,
                         candidate_id,
                         display_name,
                         feature_ids,
                         ordinal
                     ) VALUES (?, 1, ?, ?, ?::jsonb, ?)
                     """)) {
            statement.setObject(1, voteId);
            statement.setString(2, candidateId);
            statement.setString(3, candidateId);
            statement.setString(4, "[\"" + candidateId + "\"]");
            statement.setInt(5, ordinal);
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
