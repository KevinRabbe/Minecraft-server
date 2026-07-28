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

/** Read-only bounded reconciliation of clan Coin treasury state against immutable transfer and ledger evidence. */
public final class ClanTreasuryIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String DEPOSIT = "CLAN_TREASURY_DEPOSIT";
    private static final String WITHDRAW = "CLAN_TREASURY_WITHDRAW";

    private final DataSource dataSource;

    public ClanTreasuryIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyOperationShape(connection, issues, maxIssues);
            verifyStateEvidence(connection, issues, maxIssues);
            verifyLedgerEvidence(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyOperationShape(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation.operation_id, operation.operation_type
                FROM processed_operations operation
                WHERE operation.operation_type IN (?, ?)
                  AND (
                       jsonb_typeof(operation.result) IS DISTINCT FROM 'object'
                    OR operation.result ->> 'clan_id' IS NULL
                    OR operation.result ->> 'player_id' IS NULL
                    OR (operation.result ->> 'amount_minor' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                    OR operation.result ->> 'reason' IS NULL
                    OR BTRIM(operation.result ->> 'reason') = ''
                    OR jsonb_typeof(operation.result -> 'result') IS DISTINCT FROM 'object'
                    OR operation.result -> 'result' ->> 'clan_id'
                         IS DISTINCT FROM operation.result ->> 'clan_id'
                    OR operation.result -> 'result' ->> 'player_id'
                         IS DISTINCT FROM operation.result ->> 'player_id'
                    OR operation.result -> 'result' ->> 'amount_minor'
                         IS DISTINCT FROM operation.result ->> 'amount_minor'
                    OR (operation.result -> 'result' ->> 'treasury_balance_minor' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                    OR (operation.result -> 'result' ->> 'treasury_state_version' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                    OR operation.result -> 'result' ->> 'treasury_updated_at' IS NULL
                    OR BTRIM(operation.result -> 'result' ->> 'treasury_updated_at') = ''
                    OR (operation.result -> 'result' ->> 'wallet_balance_minor' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                    OR (operation.result -> 'result' ->> 'wallet_state_version' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                    OR NOT EXISTS (
                         SELECT 1 FROM clans clan
                         WHERE clan.clan_id::TEXT = operation.result ->> 'clan_id'
                       )
                    OR NOT EXISTS (
                         SELECT 1 FROM players player
                         WHERE player.player_id::TEXT = operation.result ->> 'player_id'
                       )
                  )
                ORDER BY operation.operation_id
                LIMIT ?
                """)) {
            statement.setString(1, DEPOSIT);
            statement.setString(2, WITHDRAW);
            statement.setInt(3, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_TREASURY_OPERATION_EVIDENCE_MISMATCH",
                            operationId.toString(),
                            "Processed clan treasury transfer has an invalid frozen result shape for "
                                    + rows.getString("operation_type")
                    ));
                }
            }
        }
    }

    private static void verifyStateEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH treasury_operations AS (
                    SELECT operation_id,
                           operation_type,
                           result ->> 'clan_id' AS clan_id_text,
                           CASE
                             WHEN result ->> 'amount_minor' ~ '^[1-9][0-9]*$'
                             THEN (result ->> 'amount_minor')::NUMERIC
                           END AS amount_minor,
                           CASE
                             WHEN result -> 'result' ->> 'treasury_state_version' ~ '^[1-9][0-9]*$'
                             THEN (result -> 'result' ->> 'treasury_state_version')::BIGINT
                           END AS treasury_state_version,
                           CASE
                             WHEN result -> 'result' ->> 'treasury_balance_minor' ~ '^[0-9]+$'
                             THEN (result -> 'result' ->> 'treasury_balance_minor')::NUMERIC
                           END AS treasury_balance_minor
                    FROM processed_operations
                    WHERE operation_type IN (?, ?)
                ),
                operation_summary AS (
                    SELECT clan_id_text,
                           COUNT(*)::BIGINT AS operation_count,
                           COUNT(treasury_state_version)::BIGINT AS valid_version_count,
                           COUNT(DISTINCT treasury_state_version)::BIGINT AS distinct_version_count,
                           MIN(treasury_state_version) AS minimum_version,
                           MAX(treasury_state_version) AS maximum_version
                    FROM treasury_operations
                    WHERE clan_id_text IS NOT NULL
                    GROUP BY clan_id_text
                ),
                latest_operation AS (
                    SELECT DISTINCT ON (clan_id_text)
                           clan_id_text,
                           treasury_state_version,
                           treasury_balance_minor
                    FROM treasury_operations
                    WHERE clan_id_text IS NOT NULL AND treasury_state_version IS NOT NULL
                    ORDER BY clan_id_text, treasury_state_version DESC, operation_id DESC
                ),
                ordered_history AS (
                    SELECT clan_id_text,
                           operation_type,
                           amount_minor,
                           treasury_state_version,
                           treasury_balance_minor,
                           LAG(treasury_balance_minor) OVER (
                               PARTITION BY clan_id_text ORDER BY treasury_state_version, operation_id
                           ) AS previous_balance
                    FROM treasury_operations
                    WHERE clan_id_text IS NOT NULL
                      AND amount_minor IS NOT NULL
                      AND treasury_state_version IS NOT NULL
                      AND treasury_balance_minor IS NOT NULL
                ),
                broken_history AS (
                    SELECT DISTINCT clan_id_text
                    FROM ordered_history
                    WHERE CASE operation_type
                            WHEN 'CLAN_TREASURY_DEPOSIT' THEN
                                treasury_balance_minor <> COALESCE(previous_balance, 0::NUMERIC) + amount_minor
                            WHEN 'CLAN_TREASURY_WITHDRAW' THEN
                                treasury_balance_minor + amount_minor <> COALESCE(previous_balance, 0::NUMERIC)
                            ELSE TRUE
                          END
                ),
                clan_ledger AS (
                    SELECT related_entity_id AS clan_id_text,
                           COALESCE(SUM(
                               CASE direction WHEN 'CREDIT' THEN amount::NUMERIC ELSE -amount::NUMERIC END
                           ), 0::NUMERIC) AS ledger_net_minor
                    FROM economic_ledger
                    WHERE player_id IS NULL
                      AND asset_type = 'CURRENCY'
                      AND asset_id = 'coin'
                      AND related_entity_id IN (SELECT clan_id::TEXT FROM clans)
                    GROUP BY related_entity_id
                )
                SELECT clan.clan_id
                FROM clans clan
                LEFT JOIN clan_treasuries treasury ON treasury.clan_id = clan.clan_id
                LEFT JOIN operation_summary summary ON summary.clan_id_text = clan.clan_id::TEXT
                LEFT JOIN latest_operation latest ON latest.clan_id_text = clan.clan_id::TEXT
                LEFT JOIN broken_history broken ON broken.clan_id_text = clan.clan_id::TEXT
                LEFT JOIN clan_ledger ledger ON ledger.clan_id_text = clan.clan_id::TEXT
                WHERE treasury.clan_id IS NULL
                   OR (
                        summary.clan_id_text IS NULL
                        AND (
                             treasury.balance_minor <> 0
                          OR treasury.state_version <> 0
                          OR COALESCE(ledger.ledger_net_minor, 0::NUMERIC) <> 0
                        )
                      )
                   OR (
                        summary.clan_id_text IS NOT NULL
                        AND (
                             summary.operation_count IS DISTINCT FROM summary.valid_version_count
                          OR summary.operation_count IS DISTINCT FROM summary.distinct_version_count
                          OR summary.minimum_version IS DISTINCT FROM 1
                          OR summary.maximum_version IS DISTINCT FROM summary.operation_count
                          OR treasury.state_version IS DISTINCT FROM summary.maximum_version
                          OR treasury.balance_minor::NUMERIC IS DISTINCT FROM latest.treasury_balance_minor
                          OR treasury.balance_minor::NUMERIC
                               IS DISTINCT FROM COALESCE(ledger.ledger_net_minor, 0::NUMERIC)
                          OR broken.clan_id_text IS NOT NULL
                        )
                      )
                ORDER BY clan.clan_id
                LIMIT ?
                """)) {
            statement.setString(1, DEPOSIT);
            statement.setString(2, WITHDRAW);
            statement.setInt(3, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID clanId = rows.getObject("clan_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_TREASURY_STATE_EVIDENCE_MISMATCH",
                            clanId.toString(),
                            "Clan treasury balance/version does not reconcile with contiguous transfer history and clan-side Coin ledger custody"
                    ));
                }
            }
        }
    }

    private static void verifyLedgerEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH valid_operations AS (
                    SELECT operation_id,
                           operation_type,
                           result ->> 'clan_id' AS clan_id_text,
                           result ->> 'player_id' AS player_id_text,
                           result ->> 'amount_minor' AS amount_minor,
                           result ->> 'reason' AS reason
                    FROM processed_operations
                    WHERE operation_type IN (?, ?)
                      AND jsonb_typeof(result) = 'object'
                      AND result ->> 'clan_id' IS NOT NULL
                      AND result ->> 'player_id' IS NOT NULL
                      AND result ->> 'amount_minor' ~ '^[1-9][0-9]*$'
                      AND result ->> 'reason' IS NOT NULL
                      AND BTRIM(result ->> 'reason') <> ''
                )
                SELECT operation.operation_id, operation.operation_type
                FROM valid_operations operation
                WHERE (
                        SELECT COUNT(*) FROM economic_ledger ledger
                        WHERE ledger.operation_id = operation.operation_id
                      ) <> 2
                   OR (
                        operation.operation_type = ?
                        AND (
                             (SELECT COUNT(*) FROM economic_ledger ledger
                              WHERE ledger.operation_id = operation.operation_id
                                AND ledger.line_no = 0
                                AND ledger.player_id::TEXT = operation.player_id_text
                                AND ledger.asset_type = 'CURRENCY'
                                AND ledger.asset_id = 'coin'
                                AND ledger.amount::TEXT = operation.amount_minor
                                AND ledger.direction = 'DEBIT'
                                AND ledger.reason = operation.reason
                                AND ledger.related_entity_id IS NULL) <> 1
                          OR (SELECT COUNT(*) FROM economic_ledger ledger
                              WHERE ledger.operation_id = operation.operation_id
                                AND ledger.line_no = 1
                                AND ledger.player_id IS NULL
                                AND ledger.asset_type = 'CURRENCY'
                                AND ledger.asset_id = 'coin'
                                AND ledger.amount::TEXT = operation.amount_minor
                                AND ledger.direction = 'CREDIT'
                                AND ledger.reason = operation.reason
                                AND ledger.related_entity_id = operation.clan_id_text) <> 1
                        )
                      )
                   OR (
                        operation.operation_type = ?
                        AND (
                             (SELECT COUNT(*) FROM economic_ledger ledger
                              WHERE ledger.operation_id = operation.operation_id
                                AND ledger.line_no = 0
                                AND ledger.player_id IS NULL
                                AND ledger.asset_type = 'CURRENCY'
                                AND ledger.asset_id = 'coin'
                                AND ledger.amount::TEXT = operation.amount_minor
                                AND ledger.direction = 'DEBIT'
                                AND ledger.reason = operation.reason
                                AND ledger.related_entity_id = operation.clan_id_text) <> 1
                          OR (SELECT COUNT(*) FROM economic_ledger ledger
                              WHERE ledger.operation_id = operation.operation_id
                                AND ledger.line_no = 1
                                AND ledger.player_id::TEXT = operation.player_id_text
                                AND ledger.asset_type = 'CURRENCY'
                                AND ledger.asset_id = 'coin'
                                AND ledger.amount::TEXT = operation.amount_minor
                                AND ledger.direction = 'CREDIT'
                                AND ledger.reason = operation.reason
                                AND ledger.related_entity_id IS NULL) <> 1
                        )
                      )
                ORDER BY operation.operation_id
                LIMIT ?
                """)) {
            statement.setString(1, DEPOSIT);
            statement.setString(2, WITHDRAW);
            statement.setString(3, DEPOSIT);
            statement.setString(4, WITHDRAW);
            statement.setInt(5, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_TREASURY_LEDGER_EVIDENCE_MISMATCH",
                            operationId.toString(),
                            "Clan treasury transfer does not retain its exact player↔clan Coin ledger evidence for "
                                    + rows.getString("operation_type")
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
