package io.github.kevinrabbe.minecraftserver.common.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Read-only, bounded projection for player-facing expansion voting. Mutation remains in {@link ExpansionVoteRepository}. */
public final class ExpansionVoteQueryRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LIMIT = 20;

    private final DataSource dataSource;

    public ExpansionVoteQueryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Returns currently OPEN votes whose configured time window still accepts ballots, earliest closing first. */
    public List<ExpansionVoteView> listOpen(UUID playerId, int limit) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                LinkedHashMap<UUID, ViewBuilder> views = new LinkedHashMap<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        WITH active_votes AS (
                            SELECT vote_id,
                                   candidate_set_version,
                                   status,
                                   opens_at,
                                   closes_at,
                                   winning_candidate_id,
                                   resolution_operation_id,
                                   resolved_at
                            FROM expansion_votes
                            WHERE status = 'OPEN'
                              AND opens_at <= NOW()
                              AND closes_at > NOW()
                            ORDER BY closes_at ASC, vote_id ASC
                            LIMIT ?
                        )
                        SELECT v.vote_id,
                               v.candidate_set_version,
                               v.status,
                               v.opens_at,
                               v.closes_at,
                               v.winning_candidate_id,
                               v.resolution_operation_id,
                               v.resolved_at,
                               c.candidate_id,
                               c.display_name,
                               c.feature_ids::text AS feature_ids,
                               c.resulting_world_era_id,
                               c.ordinal,
                               b.candidate_set_version AS ballot_candidate_set_version,
                               b.candidate_id AS ballot_candidate_id,
                               b.cast_at AS ballot_cast_at
                        FROM active_votes v
                        JOIN expansion_vote_candidates c
                          ON c.vote_id = v.vote_id
                         AND c.candidate_set_version = v.candidate_set_version
                        LEFT JOIN expansion_ballots b
                          ON b.vote_id = v.vote_id
                         AND b.player_id = ?
                        ORDER BY v.closes_at ASC, v.vote_id ASC, c.ordinal ASC
                        """)) {
                    statement.setInt(1, limit);
                    statement.setObject(2, playerId);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            UUID voteId = rows.getObject("vote_id", UUID.class);
                            ViewBuilder builder = views.computeIfAbsent(
                                    voteId,
                                    ignored -> new ViewBuilder(readVote(rows), readBallot(rows, voteId, playerId))
                            );
                            builder.addCandidate(readCandidate(rows));
                        }
                    }
                }

                ArrayList<ExpansionVoteView> result = new ArrayList<>(views.size());
                for (ViewBuilder builder : views.values()) {
                    result.add(builder.build());
                }
                connection.commit();
                return List.copyOf(result);
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static ExpansionVoteSnapshot readVote(ResultSet row) {
        try {
            Timestamp resolvedAt = row.getTimestamp("resolved_at");
            return new ExpansionVoteSnapshot(
                    row.getObject("vote_id", UUID.class),
                    row.getInt("candidate_set_version"),
                    ExpansionVoteStatus.valueOf(row.getString("status")),
                    row.getTimestamp("opens_at").toInstant(),
                    row.getTimestamp("closes_at").toInstant(),
                    row.getString("winning_candidate_id"),
                    row.getObject("resolution_operation_id", UUID.class),
                    resolvedAt == null ? null : resolvedAt.toInstant()
            );
        } catch (SQLException exception) {
            throw new ExpansionVoteException("Could not read expansion vote projection", exception);
        }
    }

    private static ExpansionCandidate readCandidate(ResultSet row) throws SQLException {
        String eraId = row.getString("resulting_world_era_id");
        return new ExpansionCandidate(
                row.getString("candidate_id"),
                row.getString("display_name"),
                readStringList(row.getString("feature_ids")),
                eraId == null ? null : new WorldEraId(eraId)
        );
    }

    private static ExpansionBallot readBallot(ResultSet row, UUID voteId, UUID playerId) {
        try {
            String candidateId = row.getString("ballot_candidate_id");
            if (candidateId == null) {
                return null;
            }
            return new ExpansionBallot(
                    voteId,
                    playerId,
                    row.getInt("ballot_candidate_set_version"),
                    candidateId,
                    row.getTimestamp("ballot_cast_at").toInstant()
            );
        } catch (SQLException exception) {
            throw new ExpansionVoteException("Could not read expansion ballot projection", exception);
        }
    }

    private static List<String> readStringList(String json) {
        try {
            List<String> values = JSON.readValue(json, new TypeReference<>() { });
            return List.copyOf(values);
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new ExpansionVoteException("Could not parse expansion candidate feature IDs", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static final class ViewBuilder {
        private final ExpansionVoteSnapshot vote;
        private final ExpansionBallot ballot;
        private final ArrayList<ExpansionCandidate> candidates = new ArrayList<>();

        private ViewBuilder(ExpansionVoteSnapshot vote, ExpansionBallot ballot) {
            this.vote = vote;
            this.ballot = ballot;
        }

        private void addCandidate(ExpansionCandidate candidate) {
            candidates.add(candidate);
        }

        private ExpansionVoteView build() {
            return new ExpansionVoteView(vote, candidates, ballot);
        }
    }
}
