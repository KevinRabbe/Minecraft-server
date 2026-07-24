package io.github.kevinrabbe.minecraftserver.common.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.HexFormat;

/** PostgreSQL authority for player-directed expansion votes and their feature/world-era consequences. */
public final class ExpansionVoteRepository {
    private static final String SCHEDULE_OPERATION = "EXPANSION_VOTE_SCHEDULE";
    private static final String OPEN_OPERATION = "EXPANSION_VOTE_OPEN";
    private static final String BALLOT_OPERATION = "EXPANSION_VOTE_BALLOT";
    private static final String RESOLVE_OPERATION = "EXPANSION_VOTE_RESOLVE";
    private static final String HISTORY_EVENT = "EXPANSION_VOTE_RESOLVED";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final Clock clock;

    public ExpansionVoteRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public ExpansionVoteRepository(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ExpansionVoteSnapshot load(UUID voteId) throws SQLException {
        Objects.requireNonNull(voteId, "voteId");
        try (Connection connection = dataSource.getConnection()) {
            return readVote(connection, voteId, false);
        }
    }

    public List<ExpansionCandidate> loadCandidates(UUID voteId) throws SQLException {
        Objects.requireNonNull(voteId, "voteId");
        try (Connection connection = dataSource.getConnection()) {
            ExpansionVoteSnapshot vote = readVote(connection, voteId, false);
            return readCandidates(connection, voteId, vote.candidateSetVersion());
        }
    }

    public ExpansionVoteSnapshot schedule(
            UUID operationId,
            ExpansionVoteDefinition definition,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(definition, "definition");
        String normalizedReason = requireReason(reason);
        String requestSha = scheduleRequestSha(definition);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedSchedule> processed = findProcessedSchedule(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedSchedule previous = processed.orElseThrow();
                    previous.requireSameRequest(definition.voteId(), requestSha, normalizedReason, operationId);
                    ExpansionVoteSnapshot result = readVote(connection, definition.voteId(), false);
                    connection.commit();
                    return result;
                }

                insertVote(connection, definition);
                int ordinal = 0;
                for (ExpansionCandidate candidate : definition.candidates()) {
                    insertCandidate(
                            connection,
                            definition.voteId(),
                            definition.candidateSetVersion(),
                            candidate,
                            ordinal++
                    );
                }
                ExpansionVoteSnapshot result = readVote(connection, definition.voteId(), false);
                insertProcessedSchedule(
                        connection,
                        operationId,
                        definition.voteId(),
                        requestSha,
                        normalizedReason
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ExpansionVoteSnapshot open(UUID operationId, UUID voteId, String reason) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(voteId, "voteId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedSimpleVoteAction> processed = findProcessedSimpleAction(
                        connection,
                        operationId,
                        OPEN_OPERATION
                );
                if (processed.isPresent()) {
                    ProcessedSimpleVoteAction previous = processed.orElseThrow();
                    previous.requireSameRequest(voteId, normalizedReason, operationId);
                    ExpansionVoteSnapshot result = readVote(connection, voteId, false);
                    connection.commit();
                    return result;
                }

                ExpansionVoteSnapshot vote = readVote(connection, voteId, true);
                if (vote.status() != ExpansionVoteStatus.SCHEDULED) {
                    throw new ExpansionVoteException("Only SCHEDULED expansion votes may open");
                }
                Instant now = clock.instant();
                if (now.isBefore(vote.opensAt()) || !now.isBefore(vote.closesAt())) {
                    throw new ExpansionVoteException("Expansion vote cannot open outside its configured voting window");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE expansion_votes
                        SET status = 'OPEN'
                        WHERE vote_id = ? AND status = 'SCHEDULED'
                        """)) {
                    statement.setObject(1, voteId);
                    if (statement.executeUpdate() != 1) {
                        throw new ExpansionVoteException("Expansion vote changed concurrently while opening");
                    }
                }
                insertProcessedSimpleAction(
                        connection,
                        operationId,
                        OPEN_OPERATION,
                        voteId,
                        normalizedReason
                );
                ExpansionVoteSnapshot result = readVote(connection, voteId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** One player has one effective ballot per vote; another valid operation may change it while the vote is open. */
    public ExpansionBallot castBallot(
            UUID operationId,
            UUID voteId,
            UUID playerId,
            String candidateId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(voteId, "voteId");
        Objects.requireNonNull(playerId, "playerId");
        String normalizedCandidateId = requireStableId(candidateId, "candidateId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedBallot> processed = findProcessedBallot(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedBallot previous = processed.orElseThrow();
                    previous.requireSameRequest(
                            voteId,
                            playerId,
                            normalizedCandidateId,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                ExpansionVoteSnapshot vote = readVote(connection, voteId, true);
                if (vote.status() != ExpansionVoteStatus.OPEN) {
                    throw new ExpansionVoteException("Ballots require an OPEN expansion vote");
                }
                Instant now = clock.instant();
                if (now.isBefore(vote.opensAt()) || !now.isBefore(vote.closesAt())) {
                    throw new ExpansionVoteException("Expansion vote is outside its voting window");
                }
                requirePlayer(connection, playerId);
                requireCandidate(
                        connection,
                        voteId,
                        vote.candidateSetVersion(),
                        normalizedCandidateId
                );

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO expansion_ballots(
                            vote_id,
                            player_id,
                            candidate_set_version,
                            candidate_id,
                            cast_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (vote_id, player_id)
                        DO UPDATE SET
                            candidate_set_version = EXCLUDED.candidate_set_version,
                            candidate_id = EXCLUDED.candidate_id,
                            cast_at = EXCLUDED.cast_at,
                            updated_at = EXCLUDED.updated_at
                        """)) {
                    Timestamp castAt = Timestamp.from(now);
                    statement.setObject(1, voteId);
                    statement.setObject(2, playerId);
                    statement.setInt(3, vote.candidateSetVersion());
                    statement.setString(4, normalizedCandidateId);
                    statement.setTimestamp(5, castAt);
                    statement.setTimestamp(6, castAt);
                    statement.executeUpdate();
                }

                ExpansionBallot ballot = new ExpansionBallot(
                        voteId,
                        playerId,
                        vote.candidateSetVersion(),
                        normalizedCandidateId,
                        now
                );
                insertProcessedBallot(connection, operationId, ballot, normalizedReason);
                connection.commit();
                return ballot;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Resolves only after the configured window closes. A tie remains unresolved and requires an explicit runoff. */
    public ExpansionVoteResult resolve(UUID operationId, UUID voteId, String reason) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(voteId, "voteId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedResolution> processed = findProcessedResolution(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedResolution previous = processed.orElseThrow();
                    previous.requireSameRequest(voteId, normalizedReason, operationId);
                    connection.commit();
                    return previous.result();
                }

                ExpansionVoteSnapshot vote = readVote(connection, voteId, true);
                if (vote.status() != ExpansionVoteStatus.OPEN) {
                    throw new ExpansionVoteException("Only OPEN expansion votes may resolve");
                }
                Instant now = clock.instant();
                if (now.isBefore(vote.closesAt())) {
                    throw new ExpansionVoteException("Expansion vote cannot resolve before closesAt");
                }

                List<ExpansionCandidate> candidates = readCandidates(
                        connection,
                        voteId,
                        vote.candidateSetVersion()
                );
                LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
                for (ExpansionCandidate candidate : candidates) {
                    counts.put(candidate.candidateId(), 0L);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT candidate_id, COUNT(*) AS ballots
                        FROM expansion_ballots
                        WHERE vote_id = ? AND candidate_set_version = ?
                        GROUP BY candidate_id
                        """)) {
                    statement.setObject(1, voteId);
                    statement.setInt(2, vote.candidateSetVersion());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            String candidateId = rows.getString("candidate_id");
                            if (!counts.containsKey(candidateId)) {
                                throw new ExpansionVoteException(
                                        "Ballot references candidate outside immutable candidate set: " + candidateId
                                );
                            }
                            counts.put(candidateId, rows.getLong("ballots"));
                        }
                    }
                }

                long maximum = counts.values().stream().mapToLong(Long::longValue).max().orElse(0L);
                List<String> tied = counts.entrySet().stream()
                        .filter(entry -> entry.getValue() == maximum)
                        .map(Map.Entry::getKey)
                        .toList();
                if (tied.size() != 1) {
                    throw new ExpansionVoteTieException(tied, maximum);
                }

                String winningCandidateId = tied.getFirst();
                ExpansionCandidate winner = candidates.stream()
                        .filter(candidate -> candidate.candidateId().equals(winningCandidateId))
                        .findFirst()
                        .orElseThrow();

                for (String featureId : winner.featureIds()) {
                    makeFeatureAvailable(connection, featureId, operationId, now);
                }
                if (winner.resultingWorldEraId() != null) {
                    startWorldEra(connection, winner.resultingWorldEraId(), operationId, now);
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE expansion_votes
                        SET status = 'RESOLVED',
                            winning_candidate_id = ?,
                            resolution_operation_id = ?,
                            resolved_at = ?
                        WHERE vote_id = ? AND status = 'OPEN'
                        """)) {
                    statement.setString(1, winningCandidateId);
                    statement.setObject(2, operationId);
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setObject(4, voteId);
                    if (statement.executeUpdate() != 1) {
                        throw new ExpansionVoteException("Expansion vote changed concurrently while resolving");
                    }
                }

                ExpansionVoteResult result = new ExpansionVoteResult(
                        voteId,
                        vote.candidateSetVersion(),
                        winningCandidateId,
                        counts,
                        now
                );
                insertHistoricalResolution(connection, result, winner);
                insertProcessedResolution(connection, operationId, result, normalizedReason);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public Optional<FeatureState> findFeature(String featureId) throws SQLException {
        String normalizedFeatureId = requireStableId(featureId, "featureId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT accessibility, source_operation_id, changed_at, state_version
                     FROM feature_states
                     WHERE feature_id = ?
                     """)) {
            statement.setString(1, normalizedFeatureId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new FeatureState(
                        normalizedFeatureId,
                        FeatureAccessibility.valueOf(row.getString("accessibility")),
                        row.getObject("source_operation_id", UUID.class),
                        row.getTimestamp("changed_at").toInstant(),
                        row.getLong("state_version")
                ));
            }
        }
    }

    private static void insertVote(Connection connection, ExpansionVoteDefinition definition) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO expansion_votes(
                    vote_id,
                    candidate_set_version,
                    status,
                    opens_at,
                    closes_at
                ) VALUES (?, ?, 'SCHEDULED', ?, ?)
                """)) {
            statement.setObject(1, definition.voteId());
            statement.setInt(2, definition.candidateSetVersion());
            statement.setTimestamp(3, Timestamp.from(definition.opensAt()));
            statement.setTimestamp(4, Timestamp.from(definition.closesAt()));
            statement.executeUpdate();
        }
    }

    private static void insertCandidate(
            Connection connection,
            UUID voteId,
            int candidateSetVersion,
            ExpansionCandidate candidate,
            int ordinal
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO expansion_vote_candidates(
                    vote_id,
                    candidate_set_version,
                    candidate_id,
                    display_name,
                    feature_ids,
                    resulting_world_era_id,
                    ordinal
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """)) {
            statement.setObject(1, voteId);
            statement.setInt(2, candidateSetVersion);
            statement.setString(3, candidate.candidateId());
            statement.setString(4, candidate.displayName());
            statement.setString(5, writeJson(candidate.featureIds()));
            if (candidate.resultingWorldEraId() == null) {
                statement.setNull(6, java.sql.Types.VARCHAR);
            } else {
                statement.setString(6, candidate.resultingWorldEraId().value());
            }
            statement.setInt(7, ordinal);
            statement.executeUpdate();
        }
    }

    private static ExpansionVoteSnapshot readVote(Connection connection, UUID voteId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT candidate_set_version,
                       status,
                       opens_at,
                       closes_at,
                       winning_candidate_id,
                       resolution_operation_id,
                       resolved_at
                FROM expansion_votes
                WHERE vote_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, voteId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ExpansionVoteException("Unknown expansion vote: " + voteId);
                }
                Timestamp resolvedAt = row.getTimestamp("resolved_at");
                return new ExpansionVoteSnapshot(
                        voteId,
                        row.getInt("candidate_set_version"),
                        ExpansionVoteStatus.valueOf(row.getString("status")),
                        row.getTimestamp("opens_at").toInstant(),
                        row.getTimestamp("closes_at").toInstant(),
                        row.getString("winning_candidate_id"),
                        row.getObject("resolution_operation_id", UUID.class),
                        resolvedAt == null ? null : resolvedAt.toInstant()
                );
            }
        }
    }

    private static List<ExpansionCandidate> readCandidates(
            Connection connection,
            UUID voteId,
            int candidateSetVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT candidate_id, display_name, feature_ids::text AS feature_ids, resulting_world_era_id
                FROM expansion_vote_candidates
                WHERE vote_id = ? AND candidate_set_version = ?
                ORDER BY ordinal ASC
                """)) {
            statement.setObject(1, voteId);
            statement.setInt(2, candidateSetVersion);
            try (ResultSet rows = statement.executeQuery()) {
                List<ExpansionCandidate> candidates = new ArrayList<>();
                while (rows.next()) {
                    String era = rows.getString("resulting_world_era_id");
                    candidates.add(new ExpansionCandidate(
                            rows.getString("candidate_id"),
                            rows.getString("display_name"),
                            readStringList(rows.getString("feature_ids")),
                            era == null ? null : new WorldEraId(era)
                    ));
                }
                if (candidates.size() < 2) {
                    throw new ExpansionVoteException("Expansion vote candidate set is incomplete");
                }
                return List.copyOf(candidates);
            }
        }
    }

    private static ExpansionCandidate requireCandidate(
            Connection connection,
            UUID voteId,
            int candidateSetVersion,
            String candidateId
    ) throws SQLException {
        return readCandidates(connection, voteId, candidateSetVersion).stream()
                .filter(candidate -> candidate.candidateId().equals(candidateId))
                .findFirst()
                .orElseThrow(() -> new ExpansionVoteException("Unknown candidate for vote: " + candidateId));
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ExpansionVoteException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static void makeFeatureAvailable(
            Connection connection,
            String featureId,
            UUID sourceOperationId,
            Instant changedAt
    ) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("""
                SELECT accessibility, state_version
                FROM feature_states
                WHERE feature_id = ?
                FOR UPDATE
                """)) {
            lock.setString(1, featureId);
            try (ResultSet row = lock.executeQuery()) {
                if (!row.next()) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO feature_states(
                                feature_id, accessibility, source_operation_id, state_version, changed_at
                            ) VALUES (?, 'AVAILABLE', ?, 0, ?)
                            """)) {
                        insert.setString(1, featureId);
                        insert.setObject(2, sourceOperationId);
                        insert.setTimestamp(3, Timestamp.from(changedAt));
                        insert.executeUpdate();
                    }
                    return;
                }
                if (FeatureAccessibility.valueOf(row.getString("accessibility")) == FeatureAccessibility.AVAILABLE) {
                    return;
                }
                long nextVersion;
                try {
                    nextVersion = Math.addExact(row.getLong("state_version"), 1L);
                } catch (ArithmeticException exception) {
                    throw new ExpansionVoteException("Feature state version overflow for " + featureId, exception);
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE feature_states
                        SET accessibility = 'AVAILABLE',
                            source_operation_id = ?,
                            state_version = ?,
                            changed_at = ?
                        WHERE feature_id = ?
                        """)) {
                    update.setObject(1, sourceOperationId);
                    update.setLong(2, nextVersion);
                    update.setTimestamp(3, Timestamp.from(changedAt));
                    update.setString(4, featureId);
                    update.executeUpdate();
                }
            }
        }
    }

    private static void startWorldEra(
            Connection connection,
            WorldEraId eraId,
            UUID sourceOperationId,
            Instant startedAt
    ) throws SQLException {
        int nextSequence;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(sequence_no), -1) + 1 AS next_sequence
                FROM world_eras
                """)) {
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                nextSequence = row.getInt("next_sequence");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO world_eras(era_id, sequence_no, source_operation_id, started_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, eraId.value());
            statement.setInt(2, nextSequence);
            statement.setObject(3, sourceOperationId);
            statement.setTimestamp(4, Timestamp.from(startedAt));
            statement.executeUpdate();
        }
    }

    private static void insertHistoricalResolution(
            Connection connection,
            ExpansionVoteResult result,
            ExpansionCandidate winner
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO historical_events(
                    event_id,
                    event_type,
                    source_kind,
                    source_id,
                    world_era_id,
                    occurred_at,
                    metadata
                ) VALUES (?, ?, 'EXPANSION_VOTE', ?, ?, ?, jsonb_build_object(
                    'winning_candidate_id', ?,
                    'ballot_counts', ?::jsonb
                ))
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, HISTORY_EVENT);
            statement.setString(3, result.voteId().toString());
            if (winner.resultingWorldEraId() == null) {
                statement.setNull(4, java.sql.Types.VARCHAR);
            } else {
                statement.setString(4, winner.resultingWorldEraId().value());
            }
            statement.setTimestamp(5, Timestamp.from(result.resolvedAt()));
            statement.setString(6, result.winningCandidateId());
            statement.setString(7, writeJson(result.ballotCounts()));
            statement.executeUpdate();
        }
    }

    private static String scheduleRequestSha(ExpansionVoteDefinition definition) {
        StringBuilder canonical = new StringBuilder()
                .append(definition.voteId()).append('|')
                .append(definition.candidateSetVersion()).append('|')
                .append(definition.opensAt()).append('|')
                .append(definition.closesAt());
        for (ExpansionCandidate candidate : definition.candidates()) {
            canonical.append('|')
                    .append(candidate.candidateId()).append('|')
                    .append(candidate.displayName()).append('|')
                    .append(String.join(",", candidate.featureIds())).append('|')
                    .append(candidate.resultingWorldEraId() == null ? "" : candidate.resultingWorldEraId().value());
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void insertProcessedSchedule(
            Connection connection,
            UUID operationId,
            UUID voteId,
            String requestSha,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'vote_id', ?, 'request_sha256', ?, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, SCHEDULE_OPERATION);
            statement.setString(3, voteId.toString());
            statement.setString(4, requestSha);
            statement.setString(5, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedSchedule> findProcessedSchedule(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'vote_id' AS vote_id,
                       result ->> 'request_sha256' AS request_sha256,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                requireOperationType(row.getString("operation_type"), SCHEDULE_OPERATION, operationId);
                return Optional.of(new ProcessedSchedule(
                        UUID.fromString(requireField(row, "vote_id")),
                        requireField(row, "request_sha256"),
                        requireField(row, "reason")
                ));
            }
        }
    }

    private static void insertProcessedSimpleAction(
            Connection connection,
            UUID operationId,
            String operationType,
            UUID voteId,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object('vote_id', ?, 'reason', ?))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, voteId.toString());
            statement.setString(4, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedSimpleVoteAction> findProcessedSimpleAction(
            Connection connection,
            UUID operationId,
            String expectedType
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'vote_id' AS vote_id,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                requireOperationType(row.getString("operation_type"), expectedType, operationId);
                return Optional.of(new ProcessedSimpleVoteAction(
                        UUID.fromString(requireField(row, "vote_id")),
                        requireField(row, "reason")
                ));
            }
        }
    }

    private static void insertProcessedBallot(
            Connection connection,
            UUID operationId,
            ExpansionBallot ballot,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'vote_id', ?,
                    'player_id', ?,
                    'candidate_set_version', ?,
                    'candidate_id', ?,
                    'cast_at', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, BALLOT_OPERATION);
            statement.setString(3, ballot.voteId().toString());
            statement.setString(4, ballot.playerId().toString());
            statement.setInt(5, ballot.candidateSetVersion());
            statement.setString(6, ballot.candidateId());
            statement.setString(7, ballot.castAt().toString());
            statement.setString(8, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedBallot> findProcessedBallot(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'vote_id' AS vote_id,
                       result ->> 'player_id' AS player_id,
                       result ->> 'candidate_set_version' AS candidate_set_version,
                       result ->> 'candidate_id' AS candidate_id,
                       result ->> 'cast_at' AS cast_at,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                requireOperationType(row.getString("operation_type"), BALLOT_OPERATION, operationId);
                ExpansionBallot ballot = new ExpansionBallot(
                        UUID.fromString(requireField(row, "vote_id")),
                        UUID.fromString(requireField(row, "player_id")),
                        Integer.parseInt(requireField(row, "candidate_set_version")),
                        requireField(row, "candidate_id"),
                        Instant.parse(requireField(row, "cast_at"))
                );
                return Optional.of(new ProcessedBallot(ballot, requireField(row, "reason")));
            }
        }
    }

    private static void insertProcessedResolution(
            Connection connection,
            UUID operationId,
            ExpansionVoteResult result,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'vote_id', ?,
                    'candidate_set_version', ?,
                    'winning_candidate_id', ?,
                    'ballot_counts', ?::jsonb,
                    'resolved_at', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, RESOLVE_OPERATION);
            statement.setString(3, result.voteId().toString());
            statement.setInt(4, result.candidateSetVersion());
            statement.setString(5, result.winningCandidateId());
            statement.setString(6, writeJson(result.ballotCounts()));
            statement.setString(7, result.resolvedAt().toString());
            statement.setString(8, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedResolution> findProcessedResolution(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'vote_id' AS vote_id,
                       result ->> 'candidate_set_version' AS candidate_set_version,
                       result ->> 'winning_candidate_id' AS winning_candidate_id,
                       result -> 'ballot_counts' AS ballot_counts,
                       result ->> 'resolved_at' AS resolved_at,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                requireOperationType(row.getString("operation_type"), RESOLVE_OPERATION, operationId);
                ExpansionVoteResult result = new ExpansionVoteResult(
                        UUID.fromString(requireField(row, "vote_id")),
                        Integer.parseInt(requireField(row, "candidate_set_version")),
                        requireField(row, "winning_candidate_id"),
                        readLongMap(requireField(row, "ballot_counts")),
                        Instant.parse(requireField(row, "resolved_at"))
                );
                return Optional.of(new ProcessedResolution(result, requireField(row, "reason")));
            }
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ExpansionVoteException("Failed to serialize expansion vote data", exception);
        }
    }

    private static List<String> readStringList(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ExpansionVoteException("Failed to read expansion candidate feature IDs", exception);
        }
    }

    private static Map<String, Long> readLongMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ExpansionVoteException("Failed to read expansion ballot counts", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String requireStableId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(fieldName + " has invalid format: " + normalized);
        }
        return normalized;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String normalized = reason.trim();
        if (!REASON_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("reason must be a stable lowercase identifier: " + normalized);
        }
        return normalized;
    }

    private static String requireField(ResultSet result, String field) throws SQLException {
        String value = result.getString(field);
        if (value == null) {
            throw new ExpansionVoteException("Processed expansion vote result is missing field: " + field);
        }
        return value;
    }

    private static void requireOperationType(String actual, String expected, UUID operationId) {
        if (!expected.equals(actual)) {
            throw new ExpansionVoteException(
                    "operation_id " + operationId + " already belongs to operation type " + actual
            );
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private record ProcessedSchedule(UUID voteId, String requestSha, String reason) {
        private void requireSameRequest(
                UUID expectedVoteId,
                String expectedRequestSha,
                String expectedReason,
                UUID operationId
        ) {
            if (!voteId.equals(expectedVoteId)
                    || !requestSha.equals(expectedRequestSha)
                    || !reason.equals(expectedReason)) {
                throw new ExpansionVoteException(
                        "operation_id reused with different expansion vote schedule request: " + operationId
                );
            }
        }
    }

    private record ProcessedSimpleVoteAction(UUID voteId, String reason) {
        private void requireSameRequest(UUID expectedVoteId, String expectedReason, UUID operationId) {
            if (!voteId.equals(expectedVoteId) || !reason.equals(expectedReason)) {
                throw new ExpansionVoteException(
                        "operation_id reused with different expansion vote action: " + operationId
                );
            }
        }
    }

    private record ProcessedBallot(ExpansionBallot result, String reason) {
        private void requireSameRequest(
                UUID voteId,
                UUID playerId,
                String candidateId,
                String expectedReason,
                UUID operationId
        ) {
            if (!result.voteId().equals(voteId)
                    || !result.playerId().equals(playerId)
                    || !result.candidateId().equals(candidateId)
                    || !reason.equals(expectedReason)) {
                throw new ExpansionVoteException(
                        "operation_id reused with different expansion ballot request: " + operationId
                );
            }
        }
    }

    private record ProcessedResolution(ExpansionVoteResult result, String reason) {
        private void requireSameRequest(UUID voteId, String expectedReason, UUID operationId) {
            if (!result.voteId().equals(voteId) || !reason.equals(expectedReason)) {
                throw new ExpansionVoteException(
                        "operation_id reused with different expansion resolution request: " + operationId
                );
            }
        }
    }
}
