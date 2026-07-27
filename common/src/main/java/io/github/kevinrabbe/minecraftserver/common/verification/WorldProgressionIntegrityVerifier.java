package io.github.kevinrabbe.minecraftserver.common.verification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only bounded reconciliation of resolved expansion votes with their durable operation, Chronicle, feature,
 * and world-era consequences.
 *
 * <p>This verifier intentionally does not claim that every Chronicle event, AVAILABLE feature, or world era must be
 * expansion-owned. It checks only relationships that {@code ExpansionVoteRepository.resolve} commits atomically for
 * one resolved vote.</p>
 */
public final class WorldProgressionIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String RESOLVE_OPERATION = "EXPANSION_VOTE_RESOLVE";
    private static final String HISTORY_SOURCE_KIND = "EXPANSION_VOTE";
    private static final String HISTORY_EVENT_TYPE = "EXPANSION_VOTE_RESOLVED";

    private final DataSource dataSource;

    public WorldProgressionIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyResolvedVoteEvidence(connection, issues, maxIssues);
            verifyResolvedVoteHistory(connection, issues, maxIssues);
            verifyWinningFeatureState(connection, issues, maxIssues);
            verifyResultingWorldEra(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyResolvedVoteEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH candidate_counts AS (
                    SELECT candidate.vote_id,
                           candidate.candidate_set_version,
                           candidate.candidate_id,
                           COUNT(ballot.player_id)::BIGINT AS ballot_count
                    FROM expansion_vote_candidates candidate
                    LEFT JOIN expansion_ballots ballot
                      ON ballot.vote_id = candidate.vote_id
                     AND ballot.candidate_set_version = candidate.candidate_set_version
                     AND ballot.candidate_id = candidate.candidate_id
                    GROUP BY candidate.vote_id,
                             candidate.candidate_set_version,
                             candidate.candidate_id
                ), expected_counts AS (
                    SELECT vote_id,
                           candidate_set_version,
                           jsonb_object_agg(candidate_id, to_jsonb(ballot_count) ORDER BY candidate_id) AS ballot_counts
                    FROM candidate_counts
                    GROUP BY vote_id, candidate_set_version
                )
                SELECT vote.vote_id,
                       vote.resolution_operation_id
                FROM expansion_votes vote
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = vote.resolution_operation_id
                LEFT JOIN expected_counts expected
                  ON expected.vote_id = vote.vote_id
                 AND expected.candidate_set_version = vote.candidate_set_version
                WHERE vote.status = 'RESOLVED'
                  AND (
                      operation.operation_id IS NULL
                      OR operation.operation_type IS DISTINCT FROM ?
                      OR operation.result ->> 'vote_id' IS DISTINCT FROM vote.vote_id::TEXT
                      OR operation.result ->> 'candidate_set_version' IS DISTINCT FROM vote.candidate_set_version::TEXT
                      OR operation.result ->> 'winning_candidate_id' IS DISTINCT FROM vote.winning_candidate_id
                      OR operation.result -> 'ballot_counts' IS DISTINCT FROM expected.ballot_counts
                  )
                ORDER BY vote.resolved_at, vote.vote_id
                LIMIT ?
                """)) {
            statement.setString(1, RESOLVE_OPERATION);
            statement.setInt(2, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID voteId = rows.getObject("vote_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "EXPANSION_RESOLUTION_EVIDENCE_MISMATCH",
                            voteId.toString(),
                            "Resolved expansion vote does not reconcile with its append-only resolution operation "
                                    + rows.getObject("resolution_operation_id", UUID.class)
                    ));
                }
            }
        }
    }

    private static void verifyResolvedVoteHistory(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH candidate_counts AS (
                    SELECT candidate.vote_id,
                           candidate.candidate_set_version,
                           candidate.candidate_id,
                           COUNT(ballot.player_id)::BIGINT AS ballot_count
                    FROM expansion_vote_candidates candidate
                    LEFT JOIN expansion_ballots ballot
                      ON ballot.vote_id = candidate.vote_id
                     AND ballot.candidate_set_version = candidate.candidate_set_version
                     AND ballot.candidate_id = candidate.candidate_id
                    GROUP BY candidate.vote_id,
                             candidate.candidate_set_version,
                             candidate.candidate_id
                ), expected_counts AS (
                    SELECT vote_id,
                           candidate_set_version,
                           jsonb_object_agg(candidate_id, to_jsonb(ballot_count) ORDER BY candidate_id) AS ballot_counts
                    FROM candidate_counts
                    GROUP BY vote_id, candidate_set_version
                )
                SELECT vote.vote_id
                FROM expansion_votes vote
                JOIN expansion_vote_candidates winner
                  ON winner.vote_id = vote.vote_id
                 AND winner.candidate_set_version = vote.candidate_set_version
                 AND winner.candidate_id = vote.winning_candidate_id
                LEFT JOIN expected_counts expected
                  ON expected.vote_id = vote.vote_id
                 AND expected.candidate_set_version = vote.candidate_set_version
                LEFT JOIN historical_events history
                  ON history.source_kind = ?
                 AND history.source_id = vote.vote_id::TEXT
                 AND history.event_type = ?
                WHERE vote.status = 'RESOLVED'
                  AND (
                      history.event_id IS NULL
                      OR history.occurred_at IS DISTINCT FROM vote.resolved_at
                      OR history.world_era_id IS DISTINCT FROM winner.resulting_world_era_id
                      OR history.metadata ->> 'winning_candidate_id' IS DISTINCT FROM vote.winning_candidate_id
                      OR history.metadata -> 'ballot_counts' IS DISTINCT FROM expected.ballot_counts
                  )
                ORDER BY vote.resolved_at, vote.vote_id
                LIMIT ?
                """)) {
            statement.setString(1, HISTORY_SOURCE_KIND);
            statement.setString(2, HISTORY_EVENT_TYPE);
            statement.setInt(3, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID voteId = rows.getObject("vote_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "EXPANSION_HISTORY_MISMATCH",
                            voteId.toString(),
                            "Resolved expansion vote does not reconcile with its immutable Chronicle resolution event"
                    ));
                }
            }
        }
    }

    private static void verifyWinningFeatureState(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH winner_features AS (
                    SELECT vote.vote_id,
                           feature.value AS feature_id
                    FROM expansion_votes vote
                    JOIN expansion_vote_candidates winner
                      ON winner.vote_id = vote.vote_id
                     AND winner.candidate_set_version = vote.candidate_set_version
                     AND winner.candidate_id = vote.winning_candidate_id
                    CROSS JOIN LATERAL jsonb_array_elements_text(winner.feature_ids) AS feature(value)
                    WHERE vote.status = 'RESOLVED'
                )
                SELECT winner.vote_id, winner.feature_id
                FROM winner_features winner
                LEFT JOIN feature_states state
                  ON state.feature_id = winner.feature_id
                WHERE state.feature_id IS NULL
                   OR state.accessibility IS DISTINCT FROM 'AVAILABLE'
                ORDER BY winner.vote_id, winner.feature_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID voteId = rows.getObject("vote_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "EXPANSION_FEATURE_STATE_MISMATCH",
                            voteId + "/" + rows.getString("feature_id"),
                            "Winning expansion feature is not AVAILABLE after the resolved vote"
                    ));
                }
            }
        }
    }

    private static void verifyResultingWorldEra(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT vote.vote_id,
                       winner.resulting_world_era_id
                FROM expansion_votes vote
                JOIN expansion_vote_candidates winner
                  ON winner.vote_id = vote.vote_id
                 AND winner.candidate_set_version = vote.candidate_set_version
                 AND winner.candidate_id = vote.winning_candidate_id
                LEFT JOIN world_eras era
                  ON era.era_id = winner.resulting_world_era_id
                WHERE vote.status = 'RESOLVED'
                  AND winner.resulting_world_era_id IS NOT NULL
                  AND (
                      era.era_id IS NULL
                      OR era.source_operation_id IS DISTINCT FROM vote.resolution_operation_id
                      OR era.started_at IS DISTINCT FROM vote.resolved_at
                  )
                ORDER BY vote.resolved_at, vote.vote_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID voteId = rows.getObject("vote_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "EXPANSION_WORLD_ERA_MISMATCH",
                            voteId + "/" + rows.getString("resulting_world_era_id"),
                            "Winning expansion world era does not match the vote resolution operation/time"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
