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

/** Read-only bounded verification for clan policy availability and authoritative member-cap counters. */
public final class ClanIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public ClanIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyPolicyExists(connection, issues, maxIssues);
            verifyMemberCounts(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyPolicyExists(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        if (issues.size() >= maxIssues) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM clan_policy
                    WHERE singleton = TRUE
                ) AS missing
                """);
             ResultSet row = statement.executeQuery()) {
            row.next();
            if (row.getBoolean("missing")) {
                issues.add(new IntegrityIssue(
                        IntegritySeverity.CRITICAL,
                        "CLAN_POLICY_MISSING",
                        null,
                        "Shared clan policy row is missing; new clan memberships will fail closed"
                ));
            }
        }
    }

    private static void verifyMemberCounts(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = Math.max(0, maxIssues - issues.size());
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH actual AS (
                    SELECT clan.clan_id,
                           COUNT(member.player_id)::BIGINT AS actual_count
                    FROM clans clan
                    LEFT JOIN clan_members member ON member.clan_id = clan.clan_id
                    GROUP BY clan.clan_id
                )
                SELECT actual.clan_id,
                       actual.actual_count,
                       tracked.member_count AS tracked_count
                FROM actual
                LEFT JOIN clan_member_counts tracked ON tracked.clan_id = actual.clan_id
                WHERE tracked.clan_id IS NULL
                   OR tracked.member_count::BIGINT <> actual.actual_count
                ORDER BY actual.clan_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID clanId = rows.getObject("clan_id", UUID.class);
                    long actualCount = rows.getLong("actual_count");
                    Object trackedRaw = rows.getObject("tracked_count");
                    String trackedText = trackedRaw == null ? "missing" : trackedRaw.toString();
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_MEMBER_COUNT_MISMATCH",
                            clanId.toString(),
                            "Clan member-cap counter does not reconcile: actual=" + actualCount
                                    + ", tracked=" + trackedText
                    ));
                }
            }
        }
    }
}
