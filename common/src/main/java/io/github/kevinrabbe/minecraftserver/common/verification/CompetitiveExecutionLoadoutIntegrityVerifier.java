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

/** Read-only bounded verification for frozen Clan-War execution loadout snapshots and their V73 seals. */
public final class CompetitiveExecutionLoadoutIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public CompetitiveExecutionLoadoutIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifySealCoverage(connection, issues, maxIssues);
            verifyItemIndexes(connection, issues, maxIssues);
            verifyLiveSnapshotAgainstCustody(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifySealCoverage(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.execution_id
                FROM competitive_executions e
                LEFT JOIN competitive_execution_loadout_seals seal
                  ON seal.execution_id = e.execution_id
                WHERE (e.activity_kind = 'CLAN_WAR' AND seal.execution_id IS NULL)
                   OR (e.activity_kind <> 'CLAN_WAR' AND seal.execution_id IS NOT NULL)
                   OR (
                       e.activity_kind <> 'CLAN_WAR'
                       AND EXISTS (
                           SELECT 1
                           FROM competitive_execution_loadout_items item
                           WHERE item.execution_id = e.execution_id
                       )
                   )
                ORDER BY e.execution_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID executionId = rows.getObject("execution_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "COMPETITIVE_LOADOUT_SEAL_MISMATCH",
                            executionId.toString(),
                            "Competitive execution loadout seal/kind evidence is inconsistent"
                    ));
                }
            }
        }
    }

    private static void verifyItemIndexes(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT item.execution_id, item.participant_index
                FROM competitive_execution_loadout_items item
                GROUP BY item.execution_id, item.participant_index
                HAVING MIN(item.loadout_item_index) <> 0
                    OR MAX(item.loadout_item_index) + 1 <> COUNT(*)
                ORDER BY item.execution_id, item.participant_index
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID executionId = rows.getObject("execution_id", UUID.class);
                    int participantIndex = rows.getInt("participant_index");
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "COMPETITIVE_LOADOUT_INDEX_MISMATCH",
                            executionId + ":" + participantIndex,
                            "Frozen competitive loadout item indexes are not contiguous from zero"
                    ));
                }
            }
        }
    }

    private static void verifyLiveSnapshotAgainstCustody(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH snapshot AS (
                    SELECT item.execution_id,
                           item.participant_index,
                           item.definition_id,
                           item.roll_state,
                           item.upgrade_level,
                           COUNT(*) AS item_count
                    FROM competitive_execution_loadout_items item
                    JOIN competitive_executions execution
                      ON execution.execution_id = item.execution_id
                    WHERE execution.activity_kind = 'CLAN_WAR'
                      AND execution.status IN ('ASSIGNED', 'ACTIVE')
                    GROUP BY item.execution_id,
                             item.participant_index,
                             item.definition_id,
                             item.roll_state,
                             item.upgrade_level
                ),
                custody AS (
                    SELECT execution.execution_id,
                           participant.participant_index,
                           item.definition_id,
                           item.roll_state,
                           item.upgrade_level,
                           COUNT(*) AS item_count
                    FROM competitive_executions execution
                    JOIN competitive_execution_participants participant
                      ON participant.execution_id = execution.execution_id
                    JOIN clan_war_items war_item
                      ON war_item.war_id = execution.activity_id
                     AND war_item.player_id = participant.player_id
                     AND war_item.released_at IS NULL
                    JOIN item_instances item
                      ON item.item_instance_id = war_item.item_instance_id
                     AND item.location_kind = 'WAR_CUSTODY'
                     AND item.location_id = execution.activity_id
                     AND item.state_version = war_item.entry_item_version
                    WHERE execution.activity_kind = 'CLAN_WAR'
                      AND execution.status IN ('ASSIGNED', 'ACTIVE')
                    GROUP BY execution.execution_id,
                             participant.participant_index,
                             item.definition_id,
                             item.roll_state,
                             item.upgrade_level
                ),
                mismatches AS (
                    SELECT COALESCE(snapshot.execution_id, custody.execution_id) AS execution_id,
                           COALESCE(snapshot.participant_index, custody.participant_index) AS participant_index
                    FROM snapshot
                    FULL OUTER JOIN custody
                      ON custody.execution_id = snapshot.execution_id
                     AND custody.participant_index = snapshot.participant_index
                     AND custody.definition_id = snapshot.definition_id
                     AND custody.roll_state = snapshot.roll_state
                     AND custody.upgrade_level = snapshot.upgrade_level
                    WHERE snapshot.item_count IS DISTINCT FROM custody.item_count
                )
                SELECT DISTINCT execution_id, participant_index
                FROM mismatches
                ORDER BY execution_id, participant_index
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID executionId = rows.getObject("execution_id", UUID.class);
                    int participantIndex = rows.getInt("participant_index");
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "COMPETITIVE_LOADOUT_CUSTODY_MISMATCH",
                            executionId + ":" + participantIndex,
                            "Live Clan-War execution snapshot does not reconcile to authoritative WAR_CUSTODY values"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
