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

/** Read-only bounded verification for isolated 1.8.9 Ranked Arena and Clan-War persistent authority. */
public final class CompetitiveIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public CompetitiveIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyRankedResults(connection, issues, maxIssues);
            verifyRankedParticipants(connection, issues, maxIssues);
            verifyRankedRatingHeads(connection, issues, maxIssues);
            verifyClanWarResults(connection, issues, maxIssues);
            verifyClanWarRosters(connection, issues, maxIssues);
            verifyClanWarCustody(connection, issues, maxIssues);
            verifyClanWarRatingHeads(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyRankedResults(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(m.match_id, r.match_id) AS match_id
                FROM ranked_matches m
                FULL OUTER JOIN ranked_match_results r ON r.match_id = m.match_id
                WHERE (
                    m.status = 'COMPLETED'
                    AND (
                        r.match_id IS NULL
                        OR m.result_operation_id IS DISTINCT FROM r.operation_id
                        OR m.winner_player_id IS DISTINCT FROM r.winner_player_id
                        OR r.loser_player_id NOT IN (m.player_a_id, m.player_b_id)
                        OR r.loser_player_id = r.winner_player_id
                        OR m.ruleset_id IS DISTINCT FROM r.ruleset_id
                        OR m.ruleset_version IS DISTINCT FROM r.ruleset_version
                        OR m.rating_policy_version IS DISTINCT FROM r.rating_policy_version
                        OR m.rating_k_factor IS DISTINCT FROM r.rating_k_factor
                    )
                ) OR (
                    r.match_id IS NOT NULL
                    AND (
                        m.match_id IS NULL
                        OR m.status IS DISTINCT FROM 'COMPLETED'
                        OR m.result_operation_id IS DISTINCT FROM r.operation_id
                        OR m.winner_player_id IS DISTINCT FROM r.winner_player_id
                    )
                )
                ORDER BY match_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID matchId = rows.getObject("match_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "RANKED_RESULT_EVIDENCE_MISMATCH",
                            matchId.toString(),
                            "Ranked match lifecycle does not reconcile to its immutable result evidence"
                    ));
                }
            }
        }
    }

    private static void verifyRankedParticipants(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT m.match_id
                FROM ranked_matches m
                LEFT JOIN ranked_match_participants p ON p.match_id = m.match_id
                GROUP BY m.match_id, m.player_a_id, m.player_b_id, m.status
                HAVING COUNT(*) FILTER (WHERE p.player_id IS NOT NULL) <> 2
                    OR COUNT(*) FILTER (WHERE p.player_id = m.player_a_id) <> 1
                    OR COUNT(*) FILTER (WHERE p.player_id = m.player_b_id) <> 1
                    OR COUNT(*) FILTER (
                        WHERE m.status IN ('CREATED', 'ACTIVE') AND p.released_at IS NOT NULL
                    ) <> 0
                    OR COUNT(*) FILTER (
                        WHERE m.status IN ('COMPLETED', 'CANCELLED') AND p.player_id IS NOT NULL AND p.released_at IS NULL
                    ) <> 0
                ORDER BY m.match_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID matchId = rows.getObject("match_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "RANKED_PARTICIPANT_EVIDENCE_MISMATCH",
                            matchId.toString(),
                            "Ranked match participant exclusivity/release evidence is inconsistent with match state"
                    ));
                }
            }
        }
    }

    private static void verifyRankedRatingHeads(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH rating_history AS (
                    SELECT m.player_a_id AS player_id,
                           r.player_a_rating_after AS expected_rating,
                           m.finished_at,
                           m.match_id
                    FROM ranked_match_results r
                    JOIN ranked_matches m ON m.match_id = r.match_id
                    UNION ALL
                    SELECT m.player_b_id,
                           r.player_b_rating_after,
                           m.finished_at,
                           m.match_id
                    FROM ranked_match_results r
                    JOIN ranked_matches m ON m.match_id = r.match_id
                ),
                latest AS (
                    SELECT DISTINCT ON (player_id)
                           player_id, expected_rating
                    FROM rating_history
                    ORDER BY player_id, finished_at DESC, match_id DESC
                )
                SELECT l.player_id, l.expected_rating, rr.rating AS actual_rating
                FROM latest l
                LEFT JOIN ranked_ratings rr ON rr.player_id = l.player_id
                WHERE rr.player_id IS NULL OR rr.rating IS DISTINCT FROM l.expected_rating
                ORDER BY l.player_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "RANKED_RATING_HEAD_MISMATCH",
                            playerId.toString(),
                            "Current Ranked rating does not match the latest immutable match-result rating"
                    ));
                }
            }
        }
    }

    private static void verifyClanWarResults(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(w.war_id, r.war_id) AS war_id
                FROM clan_wars w
                FULL OUTER JOIN clan_war_results r ON r.war_id = w.war_id
                WHERE (
                    w.status = 'COMPLETED'
                    AND (
                        r.war_id IS NULL
                        OR w.settlement_operation_id IS DISTINCT FROM r.operation_id
                        OR w.resolution_operation_id IS DISTINCT FROM r.operation_id
                        OR w.winning_clan_id IS DISTINCT FROM r.winning_clan_id
                        OR r.losing_clan_id NOT IN (w.challenger_clan_id, w.defender_clan_id)
                        OR r.losing_clan_id = r.winning_clan_id
                        OR w.ruleset_id IS DISTINCT FROM r.ruleset_id
                        OR w.ruleset_version IS DISTINCT FROM r.ruleset_version
                        OR w.rating_policy_version IS DISTINCT FROM r.rating_policy_version
                        OR w.rating_k_factor IS DISTINCT FROM r.rating_k_factor
                    )
                ) OR (
                    r.war_id IS NOT NULL
                    AND (
                        w.war_id IS NULL
                        OR w.status IS DISTINCT FROM 'COMPLETED'
                        OR w.settlement_operation_id IS DISTINCT FROM r.operation_id
                        OR w.winning_clan_id IS DISTINCT FROM r.winning_clan_id
                    )
                )
                ORDER BY war_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID warId = rows.getObject("war_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_WAR_RESULT_EVIDENCE_MISMATCH",
                            warId.toString(),
                            "Clan-War lifecycle does not reconcile to its immutable result evidence"
                    ));
                }
            }
        }
    }

    private static void verifyClanWarRosters(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT w.war_id
                FROM clan_wars w
                LEFT JOIN clan_war_rosters r ON r.war_id = w.war_id
                LEFT JOIN clan_members m ON m.clan_id = r.clan_id AND m.player_id = r.player_id
                GROUP BY w.war_id, w.status, w.challenger_clan_id, w.defender_clan_id, w.team_size
                HAVING COUNT(*) FILTER (
                        WHERE w.status IN ('ROSTER_LOCKED', 'ACTIVE')
                          AND r.clan_id = w.challenger_clan_id
                          AND r.released_at IS NULL
                    ) <> CASE WHEN w.status IN ('ROSTER_LOCKED', 'ACTIVE') THEN w.team_size ELSE 0 END
                    OR COUNT(*) FILTER (
                        WHERE w.status IN ('ROSTER_LOCKED', 'ACTIVE')
                          AND r.clan_id = w.defender_clan_id
                          AND r.released_at IS NULL
                    ) <> CASE WHEN w.status IN ('ROSTER_LOCKED', 'ACTIVE') THEN w.team_size ELSE 0 END
                    OR COUNT(*) FILTER (
                        WHERE w.status IN ('COMPLETED', 'CANCELLED', 'FAILED')
                          AND r.player_id IS NOT NULL
                          AND r.released_at IS NULL
                    ) <> 0
                    OR COUNT(*) FILTER (
                        WHERE w.status IN ('ROSTER_LOCKED', 'ACTIVE')
                          AND r.released_at IS NULL
                          AND m.player_id IS NULL
                    ) <> 0
                ORDER BY w.war_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID warId = rows.getObject("war_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_WAR_ROSTER_EVIDENCE_MISMATCH",
                            warId.toString(),
                            "Clan-War roster/release evidence is inconsistent with war lifecycle state"
                    ));
                }
            }
        }
    }

    private static void verifyClanWarCustody(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH evidence AS (
                    SELECT wi.war_id,
                           wi.item_instance_id,
                           wi.player_id,
                           wi.entry_item_version,
                           wi.released_at,
                           w.status AS war_status,
                           i.location_kind,
                           i.location_id,
                           i.state_version,
                           r.player_id AS roster_player_id
                    FROM clan_war_items wi
                    LEFT JOIN clan_wars w ON w.war_id = wi.war_id
                    LEFT JOIN item_instances i ON i.item_instance_id = wi.item_instance_id
                    LEFT JOIN clan_war_rosters r
                      ON r.war_id = wi.war_id AND r.player_id = wi.player_id
                ),
                broken_evidence AS (
                    SELECT war_id, item_instance_id
                    FROM evidence
                    WHERE war_status IS NULL
                       OR roster_player_id IS NULL
                       OR (
                           released_at IS NULL
                           AND (
                               war_status NOT IN ('ROSTER_LOCKED', 'ACTIVE')
                               OR location_kind IS DISTINCT FROM 'WAR_CUSTODY'
                               OR location_id IS DISTINCT FROM war_id
                               OR state_version IS DISTINCT FROM entry_item_version
                           )
                       )
                       OR (
                           released_at IS NOT NULL
                           AND (
                               war_status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')
                               OR (location_kind = 'WAR_CUSTODY' AND location_id = war_id)
                           )
                       )
                ),
                orphan_custody AS (
                    SELECT i.location_id AS war_id, i.item_instance_id
                    FROM item_instances i
                    LEFT JOIN clan_war_items wi
                      ON wi.item_instance_id = i.item_instance_id
                     AND wi.war_id = i.location_id
                     AND wi.released_at IS NULL
                    WHERE i.location_kind = 'WAR_CUSTODY'
                      AND wi.item_instance_id IS NULL
                )
                SELECT war_id, item_instance_id FROM broken_evidence
                UNION
                SELECT war_id, item_instance_id FROM orphan_custody
                ORDER BY war_id, item_instance_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID warId = rows.getObject("war_id", UUID.class);
                    UUID itemId = rows.getObject("item_instance_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_WAR_CUSTODY_EVIDENCE_MISMATCH",
                            warId + ":" + itemId,
                            "Clan-War item evidence does not reconcile to authoritative WAR_CUSTODY/release state"
                    ));
                }
            }
        }
    }

    private static void verifyClanWarRatingHeads(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH rating_history AS (
                    SELECT w.challenger_clan_id AS clan_id,
                           r.challenger_rating_after AS expected_rating,
                           w.finished_at,
                           w.war_id
                    FROM clan_war_results r
                    JOIN clan_wars w ON w.war_id = r.war_id
                    UNION ALL
                    SELECT w.defender_clan_id,
                           r.defender_rating_after,
                           w.finished_at,
                           w.war_id
                    FROM clan_war_results r
                    JOIN clan_wars w ON w.war_id = r.war_id
                ),
                latest AS (
                    SELECT DISTINCT ON (clan_id)
                           clan_id, expected_rating
                    FROM rating_history
                    ORDER BY clan_id, finished_at DESC, war_id DESC
                )
                SELECT l.clan_id, l.expected_rating, rr.rating AS actual_rating
                FROM latest l
                LEFT JOIN clan_war_ratings rr ON rr.clan_id = l.clan_id
                WHERE rr.clan_id IS NULL OR rr.rating IS DISTINCT FROM l.expected_rating
                ORDER BY l.clan_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID clanId = rows.getObject("clan_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_WAR_RATING_HEAD_MISMATCH",
                            clanId.toString(),
                            "Current Clan-War rating does not match the latest immutable war-result rating"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
