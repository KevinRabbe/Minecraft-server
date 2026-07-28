package io.github.kevinrabbe.minecraftserver.common.progression;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** PostgreSQL authority for skill XP and the global staged active cap. */
public final class SkillProgressionRepository {
    private static final String XP_OPERATION = "SKILL_XP_AWARD";
    private static final String CAP_OPERATION = "SKILL_CAP_ADVANCE";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;
    private final SkillProgressionCatalog catalog;

    public SkillProgressionRepository(DataSource dataSource, SkillProgressionCatalog catalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public ActiveSkillCapState loadActiveCap() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return readActiveCap(connection, false);
        }
    }

    public SkillProgressSnapshot load(UUID playerId, SkillId skillId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        SkillProgressionDefinition definition = catalog.require(Objects.requireNonNull(skillId, "skillId"));
        try (Connection connection = dataSource.getConnection()) {
            ActiveSkillCapState cap = readActiveCap(connection, false);
            Optional<SkillRow> row = findSkill(connection, playerId, skillId, false);
            long experience = row.map(SkillRow::experience).orElse(0L);
            long stateVersion = row.map(SkillRow::stateVersion).orElse(0L);
            return new SkillProgressSnapshot(
                    playerId,
                    skillId,
                    experience,
                    definition.levelForExperience(experience, cap.activeCap()),
                    cap.activeCap(),
                    stateVersion
            );
        }
    }

    /** Awards one authoritative XP event exactly once, truncating any portion beyond the active cap. */
    public SkillXpAwardResult awardExperience(
            UUID operationId,
            UUID playerId,
            SkillId skillId,
            long requestedExperience,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        SkillProgressionDefinition definition = catalog.require(Objects.requireNonNull(skillId, "skillId"));
        if (requestedExperience <= 0) {
            throw new IllegalArgumentException("requestedExperience must be > 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<SkillXpAwardResult> processed = findProcessedAward(connection, operationId);
                if (processed.isPresent()) {
                    SkillXpAwardResult previous = processed.orElseThrow();
                    requireSameAwardRequest(
                            previous,
                            playerId,
                            skillId,
                            requestedExperience,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous;
                }

                ActiveSkillCapState cap = readActiveCap(connection, true);
                ensurePlayerExists(connection, playerId);
                ensureSkillRow(connection, playerId, skillId);
                SkillRow current = findSkill(connection, playerId, skillId, true).orElseThrow();
                long capExperience = definition.experienceForLevel(cap.activeCap());
                if (current.experience() > capExperience) {
                    throw new SkillProgressionException(
                            "Persisted skill experience exceeds active-cap threshold for " + playerId + "/" + skillId
                    );
                }

                long available = capExperience - current.experience();
                long granted = Math.min(requestedExperience, available);
                long nextExperience = current.experience() + granted;
                long nextVersion = current.stateVersion();
                if (granted > 0) {
                    nextVersion = incrementVersion(current.stateVersion(), playerId, skillId);
                    updateSkill(
                            connection,
                            playerId,
                            skillId,
                            current.stateVersion(),
                            nextExperience,
                            nextVersion
                    );
                }

                int previousLevel = definition.levelForExperience(current.experience(), cap.activeCap());
                int newLevel = definition.levelForExperience(nextExperience, cap.activeCap());
                SkillXpAwardResult result = new SkillXpAwardResult(
                        playerId,
                        skillId,
                        requestedExperience,
                        granted,
                        current.experience(),
                        nextExperience,
                        previousLevel,
                        newLevel,
                        cap.activeCap(),
                        nextVersion,
                        normalizedReason
                );
                insertAwardEvidence(connection, operationId, result);
                insertProcessedAward(connection, operationId, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Advances exactly one locked stage: 50 -> 75 -> 100. Lowering/skipping stages is not supported. */
    public ActiveSkillCapState advanceActiveCap(
            UUID operationId,
            SkillCapStage targetStage,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(targetStage, "targetStage");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedCapAdvance> processed = findProcessedCapAdvance(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedCapAdvance previous = processed.orElseThrow();
                    previous.requireSameRequest(targetStage, normalizedReason, operationId);
                    connection.commit();
                    return previous.result();
                }

                ActiveSkillCapState current = readActiveCap(connection, true);
                SkillCapStage expectedNext = switch (current.stage()) {
                    case LAUNCH -> SkillCapStage.EXPANSION_75;
                    case EXPANSION_75 -> SkillCapStage.LATE_100;
                    case LATE_100 -> throw new SkillProgressionException("Active skill cap is already at level 100");
                };
                if (targetStage != expectedNext) {
                    throw new SkillProgressionException(
                            "Invalid active skill-cap transition " + current.stage() + " -> " + targetStage
                    );
                }

                long nextVersion;
                try {
                    nextVersion = Math.addExact(current.stateVersion(), 1L);
                } catch (ArithmeticException exception) {
                    throw new SkillProgressionException("progression_state version overflow", exception);
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE progression_state
                        SET active_skill_cap = ?,
                            state_version = ?,
                            source_operation_id = ?,
                            changed_at = NOW()
                        WHERE singleton = TRUE AND state_version = ?
                        RETURNING changed_at
                        """)) {
                    statement.setInt(1, targetStage.activeCap());
                    statement.setLong(2, nextVersion);
                    statement.setObject(3, operationId);
                    statement.setLong(4, current.stateVersion());
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) {
                            throw new SkillProgressionException("Active skill cap changed concurrently");
                        }
                        ActiveSkillCapState result = new ActiveSkillCapState(
                                targetStage,
                                nextVersion,
                                operationId,
                                row.getTimestamp("changed_at").toInstant()
                        );
                        insertProcessedCapAdvance(connection, operationId, result, normalizedReason);
                        connection.commit();
                        return result;
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static ActiveSkillCapState readActiveCap(Connection connection, boolean forUpdate) throws SQLException {
        String sql = """
                SELECT active_skill_cap, state_version, source_operation_id, changed_at
                FROM progression_state
                WHERE singleton = TRUE
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new SkillProgressionException("Global progression_state row is missing");
            }
            return new ActiveSkillCapState(
                    SkillCapStage.fromActiveCap(row.getInt("active_skill_cap")),
                    row.getLong("state_version"),
                    row.getObject("source_operation_id", UUID.class),
                    row.getTimestamp("changed_at").toInstant()
            );
        }
    }

    private static Optional<SkillRow> findSkill(
            Connection connection,
            UUID playerId,
            SkillId skillId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT experience, state_version
                FROM player_skills
                WHERE player_id = ? AND skill_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            statement.setString(2, skillId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SkillRow(
                        row.getLong("experience"),
                        row.getLong("state_version")
                ));
            }
        }
    }

    private static void ensureSkillRow(Connection connection, UUID playerId, SkillId skillId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_skills(player_id, skill_id)
                VALUES (?, ?)
                ON CONFLICT (player_id, skill_id) DO NOTHING
                """)) {
            statement.setObject(1, playerId);
            statement.setString(2, skillId.value());
            statement.executeUpdate();
        }
    }

    private static void ensurePlayerExists(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM players WHERE player_id = ?
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SkillProgressionException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static void updateSkill(
            Connection connection,
            UUID playerId,
            SkillId skillId,
            long expectedVersion,
            long experience,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE player_skills
                SET experience = ?, state_version = ?, updated_at = NOW()
                WHERE player_id = ? AND skill_id = ? AND state_version = ?
                """)) {
            statement.setLong(1, experience);
            statement.setLong(2, nextVersion);
            statement.setObject(3, playerId);
            statement.setString(4, skillId.value());
            statement.setLong(5, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new SkillProgressionException("Skill state changed concurrently");
            }
        }
    }

    private static void insertAwardEvidence(
            Connection connection,
            UUID operationId,
            SkillXpAwardResult result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO skill_xp_awards(
                    operation_id,
                    player_id,
                    skill_id,
                    requested_experience,
                    granted_experience,
                    previous_experience,
                    new_experience,
                    active_skill_cap,
                    reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, result.playerId());
            statement.setString(3, result.skillId().value());
            statement.setLong(4, result.requestedExperience());
            statement.setLong(5, result.grantedExperience());
            statement.setLong(6, result.previousExperience());
            statement.setLong(7, result.newExperience());
            statement.setInt(8, result.activeCap());
            statement.setString(9, result.reason());
            statement.executeUpdate();
        }
    }

    private static void insertProcessedAward(
            Connection connection,
            UUID operationId,
            SkillXpAwardResult result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'player_id', ?,
                    'skill_id', ?,
                    'requested_experience', ?,
                    'granted_experience', ?,
                    'previous_experience', ?,
                    'new_experience', ?,
                    'previous_level', ?,
                    'new_level', ?,
                    'active_cap', ?,
                    'state_version', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, XP_OPERATION);
            statement.setString(3, result.playerId().toString());
            statement.setString(4, result.skillId().value());
            statement.setLong(5, result.requestedExperience());
            statement.setLong(6, result.grantedExperience());
            statement.setLong(7, result.previousExperience());
            statement.setLong(8, result.newExperience());
            statement.setInt(9, result.previousLevel());
            statement.setInt(10, result.newLevel());
            statement.setInt(11, result.activeCap());
            statement.setLong(12, result.stateVersion());
            statement.setString(13, result.reason());
            statement.executeUpdate();
        }
    }

    private static Optional<SkillXpAwardResult> findProcessedAward(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'player_id' AS player_id,
                       result ->> 'skill_id' AS skill_id,
                       result ->> 'requested_experience' AS requested_experience,
                       result ->> 'granted_experience' AS granted_experience,
                       result ->> 'previous_experience' AS previous_experience,
                       result ->> 'new_experience' AS new_experience,
                       result ->> 'previous_level' AS previous_level,
                       result ->> 'new_level' AS new_level,
                       result ->> 'active_cap' AS active_cap,
                       result ->> 'state_version' AS state_version,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                requireOperationType(row.getString("operation_type"), XP_OPERATION, operationId);
                return Optional.of(new SkillXpAwardResult(
                        UUID.fromString(requireField(row, "player_id")),
                        new SkillId(requireField(row, "skill_id")),
                        Long.parseLong(requireField(row, "requested_experience")),
                        Long.parseLong(requireField(row, "granted_experience")),
                        Long.parseLong(requireField(row, "previous_experience")),
                        Long.parseLong(requireField(row, "new_experience")),
                        Integer.parseInt(requireField(row, "previous_level")),
                        Integer.parseInt(requireField(row, "new_level")),
                        Integer.parseInt(requireField(row, "active_cap")),
                        Long.parseLong(requireField(row, "state_version")),
                        requireField(row, "reason")
                ));
            } catch (IllegalArgumentException exception) {
                throw new SkillProgressionException("Invalid processed skill XP result for " + operationId, exception);
            }
        }
    }

    private static void insertProcessedCapAdvance(
            Connection connection,
            UUID operationId,
            ActiveSkillCapState result,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'active_cap', ?,
                    'state_version', ?,
                    'source_operation_id', ?,
                    'changed_at', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, CAP_OPERATION);
            statement.setInt(3, result.activeCap());
            statement.setLong(4, result.stateVersion());
            statement.setString(5, result.sourceOperationId().toString());
            statement.setString(6, result.changedAt().toString());
            statement.setString(7, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedCapAdvance> findProcessedCapAdvance(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'active_cap' AS active_cap,
                       result ->> 'state_version' AS state_version,
                       result ->> 'source_operation_id' AS source_operation_id,
                       result ->> 'changed_at' AS changed_at,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                requireOperationType(row.getString("operation_type"), CAP_OPERATION, operationId);
                ActiveSkillCapState result = new ActiveSkillCapState(
                        SkillCapStage.fromActiveCap(Integer.parseInt(requireField(row, "active_cap"))),
                        Long.parseLong(requireField(row, "state_version")),
                        UUID.fromString(requireField(row, "source_operation_id")),
                        java.time.Instant.parse(requireField(row, "changed_at"))
                );
                return Optional.of(new ProcessedCapAdvance(result, requireField(row, "reason")));
            } catch (IllegalArgumentException exception) {
                throw new SkillProgressionException("Invalid processed cap transition for " + operationId, exception);
            }
        }
    }

    private static void requireSameAwardRequest(
            SkillXpAwardResult previous,
            UUID playerId,
            SkillId skillId,
            long requestedExperience,
            String reason,
            UUID operationId
    ) {
        if (!previous.playerId().equals(playerId)
                || !previous.skillId().equals(skillId)
                || previous.requestedExperience() != requestedExperience
                || !previous.reason().equals(reason)) {
            throw new SkillProgressionException("operation_id reused with different skill XP request: " + operationId);
        }
    }

    private static String requireField(ResultSet result, String field) throws SQLException {
        String value = result.getString(field);
        if (value == null) {
            throw new SkillProgressionException("Processed skill result is missing field: " + field);
        }
        return value;
    }

    private static void requireOperationType(String actual, String expected, UUID operationId) {
        if (!expected.equals(actual)) {
            throw new SkillProgressionException(
                    "operation_id " + operationId + " already belongs to operation type " + actual
            );
        }
    }

    private static long incrementVersion(long current, UUID playerId, SkillId skillId) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new SkillProgressionException(
                    "skill state_version overflow for " + playerId + "/" + skillId,
                    exception
            );
        }
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

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private record SkillRow(long experience, long stateVersion) {
    }

    private record ProcessedCapAdvance(ActiveSkillCapState result, String reason) {
        private void requireSameRequest(
                SkillCapStage targetStage,
                String expectedReason,
                UUID operationId
        ) {
            if (result.stage() != targetStage || !reason.equals(expectedReason)) {
                throw new SkillProgressionException(
                        "operation_id reused with different skill cap transition request: " + operationId
                );
            }
        }
    }
}
