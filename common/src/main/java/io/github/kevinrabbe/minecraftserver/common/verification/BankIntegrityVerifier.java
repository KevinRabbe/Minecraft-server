package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.economy.BankTierCatalog;

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

/** Read-only bounded reconciliation of Bank Manager mutable state against immutable operation/ledger evidence. */
public final class BankIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String DEPOSIT = "BANK_DEPOSIT";
    private static final String WITHDRAW = "BANK_WITHDRAW";
    private static final String UPGRADE = "BANK_TIER_UPGRADE";
    private static final String INTEREST = "BANK_INTEREST_CREDIT";

    private final DataSource dataSource;
    private final Optional<BankTierCatalog> tiers;

    public BankIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tiers = Optional.empty();
    }

    public BankIntegrityVerifier(DataSource dataSource, BankTierCatalog tiers) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tiers = Optional.of(Objects.requireNonNull(tiers, "tiers"));
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
            if (tiers.isPresent()) {
                verifyCatalogState(connection, tiers.orElseThrow(), issues, maxIssues);
            }
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
                SELECT operation_id, operation_type
                FROM processed_operations
                WHERE operation_type IN (?, ?, ?, ?)
                  AND (
                       jsonb_typeof(result) IS DISTINCT FROM 'object'
                    OR result ->> 'player_id' IS NULL
                    OR (result ->> 'bank_state_version' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                    OR (result ->> 'bank_balance_minor' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                    OR result ->> 'reason' IS NULL
                    OR (
                        operation_type IN (?, ?)
                        AND (
                             (result ->> 'amount_minor' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                          OR (result ->> 'wallet_balance_minor' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                          OR (result ->> 'wallet_state_version' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                        )
                    )
                    OR (
                        operation_type = ?
                        AND (
                             (result ->> 'previous_tier' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                          OR (result ->> 'new_tier' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                          OR (result ->> 'cost_minor' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                          OR (result ->> 'wallet_balance_minor' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                          OR (result ->> 'wallet_state_version' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                          OR CASE
                               WHEN (result ->> 'previous_tier' ~ '^[0-9]+$')
                                AND (result ->> 'new_tier' ~ '^[1-9][0-9]*$')
                               THEN (result ->> 'new_tier')::BIGINT <> (result ->> 'previous_tier')::BIGINT + 1
                               ELSE FALSE
                             END
                        )
                    )
                    OR (
                        operation_type = ?
                        AND (
                             (result ->> 'credited_minor' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                          OR (result ->> 'interest_period' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$') IS DISTINCT FROM TRUE
                        )
                    )
                  )
                ORDER BY operation_id
                LIMIT ?
                """)) {
            statement.setString(1, DEPOSIT);
            statement.setString(2, WITHDRAW);
            statement.setString(3, UPGRADE);
            statement.setString(4, INTEREST);
            statement.setString(5, DEPOSIT);
            statement.setString(6, WITHDRAW);
            statement.setString(7, UPGRADE);
            statement.setString(8, INTEREST);
            statement.setInt(9, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BANK_OPERATION_EVIDENCE_MISMATCH",
                            operationId.toString(),
                            "Processed Bank Manager operation has an invalid frozen result shape for "
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
                WITH bank_operations AS (
                    SELECT operation_id,
                           operation_type,
                           result ->> 'player_id' AS player_id_text,
                           CASE
                             WHEN result ->> 'bank_state_version' ~ '^[1-9][0-9]*$'
                             THEN (result ->> 'bank_state_version')::BIGINT
                           END AS bank_state_version,
                           result ->> 'bank_balance_minor' AS bank_balance_minor,
                           result ->> 'new_tier' AS new_tier,
                           result ->> 'interest_period' AS interest_period
                    FROM processed_operations
                    WHERE operation_type IN (?, ?, ?, ?)
                ),
                operation_summary AS (
                    SELECT player_id_text,
                           COUNT(*)::BIGINT AS operation_count,
                           COUNT(bank_state_version)::BIGINT AS valid_version_count,
                           COUNT(DISTINCT bank_state_version)::BIGINT AS distinct_version_count,
                           MIN(bank_state_version) AS minimum_version,
                           MAX(bank_state_version) AS maximum_version
                    FROM bank_operations
                    WHERE player_id_text IS NOT NULL
                    GROUP BY player_id_text
                ),
                latest_operation AS (
                    SELECT DISTINCT ON (player_id_text)
                           player_id_text,
                           bank_state_version,
                           bank_balance_minor
                    FROM bank_operations
                    WHERE player_id_text IS NOT NULL AND bank_state_version IS NOT NULL
                    ORDER BY player_id_text, bank_state_version DESC, operation_id DESC
                ),
                latest_upgrade AS (
                    SELECT DISTINCT ON (player_id_text)
                           player_id_text,
                           bank_state_version,
                           new_tier
                    FROM bank_operations
                    WHERE operation_type = ?
                      AND player_id_text IS NOT NULL
                      AND bank_state_version IS NOT NULL
                    ORDER BY player_id_text, bank_state_version DESC, operation_id DESC
                ),
                latest_interest AS (
                    SELECT DISTINCT ON (player_id_text)
                           player_id_text,
                           bank_state_version,
                           interest_period
                    FROM bank_operations
                    WHERE operation_type = ?
                      AND player_id_text IS NOT NULL
                      AND bank_state_version IS NOT NULL
                    ORDER BY player_id_text, bank_state_version DESC, operation_id DESC
                )
                SELECT COALESCE(account.player_id::TEXT, summary.player_id_text) AS subject_id,
                       account.player_id,
                       account.balance_minor,
                       account.tier,
                       account.state_version,
                       account.last_interest_period,
                       summary.operation_count,
                       summary.valid_version_count,
                       summary.distinct_version_count,
                       summary.minimum_version,
                       summary.maximum_version,
                       latest.bank_balance_minor AS latest_balance,
                       upgrade.new_tier AS latest_tier,
                       interest.interest_period AS latest_interest_period
                FROM bank_accounts account
                FULL OUTER JOIN operation_summary summary
                  ON summary.player_id_text = account.player_id::TEXT
                LEFT JOIN latest_operation latest
                  ON latest.player_id_text = summary.player_id_text
                LEFT JOIN latest_upgrade upgrade
                  ON upgrade.player_id_text = summary.player_id_text
                LEFT JOIN latest_interest interest
                  ON interest.player_id_text = summary.player_id_text
                WHERE account.player_id IS NULL
                   OR (
                       summary.player_id_text IS NULL
                       AND (
                            account.balance_minor <> 0
                         OR account.tier <> 0
                         OR account.state_version <> 0
                         OR account.last_interest_period IS NOT NULL
                       )
                   )
                   OR (
                       summary.player_id_text IS NOT NULL
                       AND (
                            summary.operation_count IS DISTINCT FROM summary.valid_version_count
                         OR summary.operation_count IS DISTINCT FROM summary.distinct_version_count
                         OR summary.minimum_version IS DISTINCT FROM 1
                         OR summary.maximum_version IS DISTINCT FROM summary.operation_count
                         OR account.state_version IS DISTINCT FROM summary.maximum_version
                         OR account.balance_minor::TEXT IS DISTINCT FROM latest.bank_balance_minor
                         OR account.tier::TEXT IS DISTINCT FROM COALESCE(upgrade.new_tier, '0')
                         OR account.last_interest_period::TEXT IS DISTINCT FROM interest.interest_period
                       )
                   )
                ORDER BY subject_id
                LIMIT ?
                """)) {
            statement.setString(1, DEPOSIT);
            statement.setString(2, WITHDRAW);
            statement.setString(3, UPGRADE);
            statement.setString(4, INTEREST);
            statement.setString(5, UPGRADE);
            statement.setString(6, INTEREST);
            statement.setInt(7, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String subject = rows.getString("subject_id");
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BANK_STATE_EVIDENCE_MISMATCH",
                            subject,
                            "Bank Manager mutable balance/tier/version/interest state does not reconcile with immutable bank operation history"
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
                SELECT operation.operation_id, operation.operation_type
                FROM processed_operations operation
                WHERE operation.operation_type IN (?, ?, ?, ?)
                  AND (
                       (
                           operation.operation_type IN (?, ?)
                           AND (
                                (SELECT COUNT(*) FROM economic_ledger ledger
                                 WHERE ledger.operation_id = operation.operation_id
                                   AND ledger.asset_type = 'CURRENCY' AND ledger.asset_id = 'coin') <> 2
                             OR (SELECT COUNT(*) FROM economic_ledger ledger
                                 WHERE ledger.operation_id = operation.operation_id
                                   AND ledger.player_id::TEXT = operation.result ->> 'player_id'
                                   AND ledger.asset_type = 'CURRENCY' AND ledger.asset_id = 'coin'
                                   AND ledger.amount::TEXT = operation.result ->> 'amount_minor'
                                   AND ledger.direction = 'DEBIT'
                                   AND ledger.reason = operation.result ->> 'reason') <> 1
                             OR (SELECT COUNT(*) FROM economic_ledger ledger
                                 WHERE ledger.operation_id = operation.operation_id
                                   AND ledger.player_id::TEXT = operation.result ->> 'player_id'
                                   AND ledger.asset_type = 'CURRENCY' AND ledger.asset_id = 'coin'
                                   AND ledger.amount::TEXT = operation.result ->> 'amount_minor'
                                   AND ledger.direction = 'CREDIT'
                                   AND ledger.reason = operation.result ->> 'reason') <> 1
                           )
                       )
                    OR (
                           operation.operation_type = ?
                           AND (
                                CASE WHEN operation.result ->> 'cost_minor' = '0' THEN
                                    (SELECT COUNT(*) FROM economic_ledger ledger
                                     WHERE ledger.operation_id = operation.operation_id) <> 0
                                ELSE
                                    (SELECT COUNT(*) FROM economic_ledger ledger
                                     WHERE ledger.operation_id = operation.operation_id
                                       AND ledger.player_id::TEXT = operation.result ->> 'player_id'
                                       AND ledger.asset_type = 'CURRENCY' AND ledger.asset_id = 'coin'
                                       AND ledger.amount::TEXT = operation.result ->> 'cost_minor'
                                       AND ledger.direction = 'DEBIT'
                                       AND ledger.reason = operation.result ->> 'reason') <> 1
                                    OR (SELECT COUNT(*) FROM economic_ledger ledger
                                        WHERE ledger.operation_id = operation.operation_id) <> 1
                                END
                           )
                       )
                    OR (
                           operation.operation_type = ?
                           AND (
                                CASE WHEN operation.result ->> 'credited_minor' = '0' THEN
                                    (SELECT COUNT(*) FROM economic_ledger ledger
                                     WHERE ledger.operation_id = operation.operation_id) <> 0
                                ELSE
                                    (SELECT COUNT(*) FROM economic_ledger ledger
                                     WHERE ledger.operation_id = operation.operation_id
                                       AND ledger.player_id::TEXT = operation.result ->> 'player_id'
                                       AND ledger.asset_type = 'CURRENCY' AND ledger.asset_id = 'coin'
                                       AND ledger.amount::TEXT = operation.result ->> 'credited_minor'
                                       AND ledger.direction = 'CREDIT'
                                       AND ledger.reason = operation.result ->> 'reason') <> 1
                                    OR (SELECT COUNT(*) FROM economic_ledger ledger
                                        WHERE ledger.operation_id = operation.operation_id) <> 1
                                END
                           )
                       )
                  )
                ORDER BY operation.operation_id
                LIMIT ?
                """)) {
            statement.setString(1, DEPOSIT);
            statement.setString(2, WITHDRAW);
            statement.setString(3, UPGRADE);
            statement.setString(4, INTEREST);
            statement.setString(5, DEPOSIT);
            statement.setString(6, WITHDRAW);
            statement.setString(7, UPGRADE);
            statement.setString(8, INTEREST);
            statement.setInt(9, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "BANK_LEDGER_EVIDENCE_MISMATCH",
                            operationId.toString(),
                            "Bank Manager processed operation does not reconcile with its append-only Coin ledger evidence"
                    ));
                }
            }
        }
    }

    private static void verifyCatalogState(
            Connection connection,
            BankTierCatalog tiers,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        int maximumTier = tiers.maxTier();
        Integer[] tierIds = new Integer[maximumTier + 1];
        Long[] capacities = new Long[maximumTier + 1];
        for (int tier = 0; tier <= maximumTier; tier++) {
            tierIds[tier] = tier;
            capacities[tier] = tiers.require(tier).capacityMinor();
        }

        Array tierArray = connection.createArrayOf("integer", tierIds);
        Array capacityArray = connection.createArrayOf("bigint", capacities);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH limits(tier, capacity_minor) AS (
                        SELECT * FROM UNNEST(?::INTEGER[], ?::BIGINT[])
                    )
                    SELECT account.player_id, account.tier, account.balance_minor, limits.capacity_minor
                    FROM bank_accounts account
                    LEFT JOIN limits ON limits.tier = account.tier
                    WHERE limits.tier IS NULL
                       OR account.balance_minor > limits.capacity_minor
                    ORDER BY account.player_id
                    LIMIT ?
                    """)) {
                statement.setArray(1, tierArray);
                statement.setArray(2, capacityArray);
                statement.setInt(3, remaining);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        UUID playerId = rows.getObject("player_id", UUID.class);
                        issues.add(new IntegrityIssue(
                                IntegritySeverity.CRITICAL,
                                "BANK_CATALOG_STATE_MISMATCH",
                                playerId.toString(),
                                "Current Bank Manager tier is unknown to the loaded catalog or its protected balance exceeds the tier capacity"
                        ));
                    }
                }
            }
        } finally {
            tierArray.free();
            capacityArray.free();
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
