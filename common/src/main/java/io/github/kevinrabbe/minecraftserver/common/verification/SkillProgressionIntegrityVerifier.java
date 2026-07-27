package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only bounded reconciliation of mutable skill state against append-only XP evidence and the loaded catalog. */
public final class SkillProgressionIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;
    private final Optional<SkillProgressionCatalog> skillCatalog;

    /** Compatibility verifier for persisted state/evidence when the active skill catalog is not available. */
    public SkillProgressionIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.skillCatalog = Optional.empty();
    }

    /** Strong verifier that also validates persisted skill IDs and cap ceilings against the loaded catalog. */
    public SkillProgressionIntegrityVerifier(DataSource dataSource, SkillProgressionCatalog skillCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.skillCatalog = Optional.of(Objects.requireNonNull(skillCatalog, "skillCatalog"));
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyStateAgainstAwardEvidence(connection, issues, maxIssues);

            Integer currentCap = readCurrentCap(connection, issues, maxIssues);
            if (currentCap != null) {
                verifyAwardCapChronology(connection, currentCap, issues, maxIssues);
            }

            if (skillCatalog.isPresent()) {
                SkillProgressionCatalog catalog = skillCatalog.orElseThrow();
                verifyKnownSkillIds(connection, catalog, issues, maxIssues);
                if (currentCap != null) {
                    verifyCurrentCapCeilings(connection, catalog, currentCap, issues, maxIssues);
                    verifyHistoricalAwardCeilings(connection, catalog, currentCap, issues, maxIssues);
                }
            }
            return List.copyOf(issues);
        }
    }

    private static void verifyStateAgainstAwardEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH award_summary AS (
                    SELECT player_id,
                           skill_id,
                           COALESCE(SUM(granted_experience), 0)::BIGINT AS granted_total,
                           COUNT(*) FILTER (WHERE granted_experience > 0)::BIGINT AS positive_grants
                    FROM skill_xp_awards
                    GROUP BY player_id, skill_id
                )
                SELECT COALESCE(state.player_id, awards.player_id) AS player_id,
                       COALESCE(state.skill_id, awards.skill_id) AS skill_id,
                       state.experience,
                       state.state_version,
                       COALESCE(awards.granted_total, 0) AS granted_total,
                       COALESCE(awards.positive_grants, 0) AS positive_grants,
                       state.player_id IS NULL AS missing_state
                FROM player_skills state
                FULL OUTER JOIN award_summary awards
                  ON awards.player_id = state.player_id
                 AND awards.skill_id = state.skill_id
                WHERE state.player_id IS NULL
                   OR state.experience IS DISTINCT FROM COALESCE(awards.granted_total, 0)
                   OR state.state_version IS DISTINCT FROM COALESCE(awards.positive_grants, 0)
                ORDER BY COALESCE(state.player_id, awards.player_id),
                         COALESCE(state.skill_id, awards.skill_id)
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    String skillId = rows.getString("skill_id");
                    boolean missingState = rows.getBoolean("missing_state");
                    String currentExperience = missingState ? "missing" : Long.toString(rows.getLong("experience"));
                    String currentVersion = missingState ? "missing" : Long.toString(rows.getLong("state_version"));
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "SKILL_PROGRESS_EVIDENCE_MISMATCH",
                            playerId + "/" + skillId,
                            "Mutable skill state does not reconcile with append-only XP evidence: experience="
                                    + currentExperience + ", grantedEvidence=" + rows.getLong("granted_total")
                                    + ", stateVersion=" + currentVersion
                                    + ", positiveGrantEvents=" + rows.getLong("positive_grants")
                    ));
                }
            }
        }
    }

    private static Integer readCurrentCap(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT active_skill_cap
                FROM progression_state
                WHERE singleton = TRUE
                """);
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                if (remaining(issues, maxIssues) > 0) {
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "SKILL_ACTIVE_CAP_MISSING",
                            "progression_state",
                            "Global staged skill-cap authority row is missing"
                    ));
                }
                return null;
            }
            int cap = row.getInt("active_skill_cap");
            if (row.next() && remaining(issues, maxIssues) > 0) {
                issues.add(new IntegrityIssue(
                        IntegritySeverity.CRITICAL,
                        "SKILL_ACTIVE_CAP_DUPLICATE",
                        "progression_state",
                        "More than one global staged skill-cap authority row exists"
                ));
            }
            return cap;
        }
    }

    private static void verifyAwardCapChronology(
            Connection connection,
            int currentCap,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, player_id, skill_id, active_skill_cap
                FROM skill_xp_awards
                WHERE active_skill_cap > ?
                ORDER BY created_at, operation_id
                LIMIT ?
                """)) {
            statement.setInt(1, currentCap);
            statement.setInt(2, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "SKILL_AWARD_CAP_CHRONOLOGY_MISMATCH",
                            operationId.toString(),
                            "XP award for player " + rows.getObject("player_id", UUID.class)
                                    + "/" + rows.getString("skill_id") + " recorded active cap "
                                    + rows.getInt("active_skill_cap") + " above current monotonic cap " + currentCap
                    ));
                }
            }
        }
    }

    private static void verifyKnownSkillIds(
            Connection connection,
            SkillProgressionCatalog catalog,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        String[] knownIds = catalog.all().stream()
                .map(definition -> definition.skillId().value())
                .toArray(String[]::new);
        Array known = connection.createArrayOf("text", knownIds);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH known(skill_id) AS (
                        SELECT UNNEST(?::TEXT[])
                    ), unknown AS (
                        SELECT 'STATE' AS source,
                               state.player_id,
                               state.skill_id,
                               NULL::UUID AS operation_id
                        FROM player_skills state
                        WHERE NOT EXISTS (SELECT 1 FROM known WHERE known.skill_id = state.skill_id)
                        UNION ALL
                        SELECT 'AWARD' AS source,
                               award.player_id,
                               award.skill_id,
                               award.operation_id
                        FROM skill_xp_awards award
                        WHERE NOT EXISTS (SELECT 1 FROM known WHERE known.skill_id = award.skill_id)
                    )
                    SELECT source, player_id, skill_id, operation_id
                    FROM unknown
                    ORDER BY skill_id, player_id, source
                    LIMIT ?
                    """)) {
                statement.setArray(1, known);
                statement.setInt(2, remaining);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String source = rows.getString("source");
                        UUID playerId = rows.getObject("player_id", UUID.class);
                        UUID operationId = rows.getObject("operation_id", UUID.class);
                        String subject = operationId == null
                                ? playerId + "/" + rows.getString("skill_id")
                                : operationId.toString();
                        issues.add(new IntegrityIssue(
                                IntegritySeverity.CRITICAL,
                                "SKILL_DEFINITION_UNKNOWN",
                                subject,
                                source + " progression state for player " + playerId
                                        + " references unknown skill " + rows.getString("skill_id")
                        ));
                    }
                }
            }
        } finally {
            known.free();
        }
    }

    private static void verifyCurrentCapCeilings(
            Connection connection,
            SkillProgressionCatalog catalog,
            int currentCap,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        List<SkillProgressionDefinition> definitions = catalog.all();
        String[] skillIds = new String[definitions.size()];
        Long[] ceilings = new Long[definitions.size()];
        for (int index = 0; index < definitions.size(); index++) {
            SkillProgressionDefinition definition = definitions.get(index);
            skillIds[index] = definition.skillId().value();
            ceilings[index] = definition.experienceForLevel(currentCap);
        }

        Array ids = connection.createArrayOf("text", skillIds);
        Array maximums = connection.createArrayOf("bigint", ceilings);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH limits(skill_id, max_experience) AS (
                        SELECT * FROM UNNEST(?::TEXT[], ?::BIGINT[])
                    )
                    SELECT state.player_id,
                           state.skill_id,
                           state.experience,
                           limits.max_experience
                    FROM player_skills state
                    JOIN limits ON limits.skill_id = state.skill_id
                    WHERE state.experience > limits.max_experience
                    ORDER BY state.player_id, state.skill_id
                    LIMIT ?
                    """)) {
                statement.setArray(1, ids);
                statement.setArray(2, maximums);
                statement.setInt(3, remaining);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        UUID playerId = rows.getObject("player_id", UUID.class);
                        String skillId = rows.getString("skill_id");
                        issues.add(new IntegrityIssue(
                                IntegritySeverity.CRITICAL,
                                "SKILL_ACTIVE_CAP_EXCEEDED",
                                playerId + "/" + skillId,
                                "Persisted XP " + rows.getLong("experience") + " exceeds active-cap " + currentCap
                                        + " ceiling " + rows.getLong("max_experience")
                        ));
                    }
                }
            }
        } finally {
            ids.free();
            maximums.free();
        }
    }

    private static void verifyHistoricalAwardCeilings(
            Connection connection,
            SkillProgressionCatalog catalog,
            int currentCap,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        List<SkillProgressionDefinition> definitions = catalog.all();
        String[] skillIds = new String[definitions.size()];
        Long[] cap50 = new Long[definitions.size()];
        Long[] cap75 = new Long[definitions.size()];
        Long[] cap100 = new Long[definitions.size()];
        for (int index = 0; index < definitions.size(); index++) {
            SkillProgressionDefinition definition = definitions.get(index);
            skillIds[index] = definition.skillId().value();
            cap50[index] = definition.experienceForLevel(50);
            cap75[index] = definition.experienceForLevel(75);
            cap100[index] = definition.experienceForLevel(100);
        }

        Array ids = connection.createArrayOf("text", skillIds);
        Array maximum50 = connection.createArrayOf("bigint", cap50);
        Array maximum75 = connection.createArrayOf("bigint", cap75);
        Array maximum100 = connection.createArrayOf("bigint", cap100);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH limits(skill_id, cap_50, cap_75, cap_100) AS (
                        SELECT * FROM UNNEST(?::TEXT[], ?::BIGINT[], ?::BIGINT[], ?::BIGINT[])
                    )
                    SELECT award.operation_id,
                           award.player_id,
                           award.skill_id,
                           award.new_experience,
                           award.active_skill_cap,
                           CASE award.active_skill_cap
                               WHEN 50 THEN limits.cap_50
                               WHEN 75 THEN limits.cap_75
                               WHEN 100 THEN limits.cap_100
                           END AS max_experience
                    FROM skill_xp_awards award
                    JOIN limits ON limits.skill_id = award.skill_id
                    WHERE award.active_skill_cap > ?
                       OR award.new_experience > CASE award.active_skill_cap
                               WHEN 50 THEN limits.cap_50
                               WHEN 75 THEN limits.cap_75
                               WHEN 100 THEN limits.cap_100
                           END
                    ORDER BY award.created_at, award.operation_id
                    LIMIT ?
                    """)) {
                statement.setArray(1, ids);
                statement.setArray(2, maximum50);
                statement.setArray(3, maximum75);
                statement.setArray(4, maximum100);
                statement.setInt(5, currentCap);
                statement.setInt(6, remaining);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        UUID operationId = rows.getObject("operation_id", UUID.class);
                        issues.add(new IntegrityIssue(
                                IntegritySeverity.CRITICAL,
                                "SKILL_AWARD_CAP_EXCEEDED",
                                operationId.toString(),
                                "XP award for player " + rows.getObject("player_id", UUID.class)
                                        + "/" + rows.getString("skill_id") + " ended at XP "
                                        + rows.getLong("new_experience") + " beyond cap "
                                        + rows.getInt("active_skill_cap") + " ceiling "
                                        + rows.getLong("max_experience")
                        ));
                    }
                }
            }
        } finally {
            ids.free();
            maximum50.free();
            maximum75.free();
            maximum100.free();
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
