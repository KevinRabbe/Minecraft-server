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

/** Read-only bounded reconciliation of clan shared-storage operation history and withdrawal evidence. */
public final class ClanStorageIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String COMMODITY_DEPOSIT = "CLAN_STORAGE_COMMODITY_DEPOSIT";
    private static final String COMMODITY_WITHDRAW = "CLAN_STORAGE_COMMODITY_WITHDRAW";
    private static final String UNIQUE_DEPOSIT = "CLAN_STORAGE_UNIQUE_DEPOSIT";
    private static final String UNIQUE_WITHDRAW = "CLAN_STORAGE_UNIQUE_WITHDRAW";

    private final DataSource dataSource;

    public ClanStorageIntegrityVerifier(DataSource dataSource) {
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
            verifyCommodityHistory(connection, issues, maxIssues);
            verifyWithdrawalDeliveries(connection, issues, maxIssues);
            verifyUniqueProvenance(connection, issues, maxIssues);
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
                WHERE operation.operation_type IN (?, ?, ?, ?)
                  AND (
                       jsonb_typeof(operation.result) IS DISTINCT FROM 'object'
                    OR jsonb_typeof(operation.result -> 'request') IS DISTINCT FROM 'object'
                    OR jsonb_typeof(operation.result -> 'result') IS DISTINCT FROM 'object'
                    OR operation.result -> 'request' ->> 'clan_id' IS NULL
                    OR operation.result -> 'request' ->> 'reason' IS NULL
                    OR BTRIM(operation.result -> 'request' ->> 'reason') = ''
                    OR NOT EXISTS (
                         SELECT 1 FROM clans clan
                         WHERE clan.clan_id::TEXT = operation.result -> 'request' ->> 'clan_id'
                       )
                    OR CASE operation.operation_type
                         WHEN 'CLAN_STORAGE_COMMODITY_DEPOSIT' THEN
                              operation.result -> 'request' ->> 'commodity_definition_id' IS NULL
                           OR (operation.result -> 'request' ->> 'quantity' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                           OR operation.result -> 'request' ->> 'session_id' IS NULL
                           OR operation.result -> 'request' ->> 'backend_id' IS NULL
                           OR (operation.result -> 'request' ->> 'expected_player_state_version' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                           OR operation.result -> 'request' ->> 'payload_sha256' IS NULL
                           OR jsonb_typeof(operation.result -> 'result' -> 'storage') IS DISTINCT FROM 'object'
                           OR operation.result -> 'result' -> 'storage' ->> 'clan_id'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'clan_id'
                           OR operation.result -> 'result' -> 'storage' ->> 'commodity_definition_id'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'commodity_definition_id'
                           OR (operation.result -> 'result' -> 'storage' ->> 'quantity' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                           OR (operation.result -> 'result' -> 'storage' ->> 'state_version' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                           OR operation.result -> 'result' -> 'storage' ->> 'updated_at' IS NULL
                           OR operation.result -> 'result' ->> 'player_id' IS NULL
                           OR NOT EXISTS (
                                SELECT 1 FROM players player
                                WHERE player.player_id::TEXT = operation.result -> 'result' ->> 'player_id'
                              )
                           OR operation.result -> 'result' ->> 'deposited_quantity'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'quantity'
                           OR (operation.result -> 'result' ->> 'player_state_version' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                         WHEN 'CLAN_STORAGE_COMMODITY_WITHDRAW' THEN
                              operation.result -> 'request' ->> 'commodity_definition_id' IS NULL
                           OR (operation.result -> 'request' ->> 'quantity' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                           OR operation.result -> 'request' ->> 'player_id' IS NULL
                           OR NOT EXISTS (
                                SELECT 1 FROM players player
                                WHERE player.player_id::TEXT = operation.result -> 'request' ->> 'player_id'
                              )
                           OR jsonb_typeof(operation.result -> 'result' -> 'storage') IS DISTINCT FROM 'object'
                           OR operation.result -> 'result' -> 'storage' ->> 'clan_id'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'clan_id'
                           OR operation.result -> 'result' -> 'storage' ->> 'commodity_definition_id'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'commodity_definition_id'
                           OR (operation.result -> 'result' -> 'storage' ->> 'quantity' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                           OR (operation.result -> 'result' -> 'storage' ->> 'state_version' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                           OR operation.result -> 'result' -> 'storage' ->> 'updated_at' IS NULL
                           OR operation.result -> 'result' ->> 'player_id'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'player_id'
                           OR operation.result -> 'result' ->> 'withdrawn_quantity'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'quantity'
                           OR operation.result -> 'result' ->> 'delivery_id' IS NULL
                         WHEN 'CLAN_STORAGE_UNIQUE_DEPOSIT' THEN
                              operation.result -> 'request' ->> 'item_instance_id' IS NULL
                           OR (operation.result -> 'request' ->> 'expected_item_state_version' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                           OR operation.result -> 'request' ->> 'session_id' IS NULL
                           OR operation.result -> 'request' ->> 'backend_id' IS NULL
                           OR (operation.result -> 'request' ->> 'expected_player_state_version' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                           OR operation.result -> 'request' ->> 'payload_sha256' IS NULL
                           OR operation.result -> 'result' ->> 'clan_id'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'clan_id'
                           OR operation.result -> 'result' ->> 'item_instance_id'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'item_instance_id'
                           OR operation.result -> 'result' ->> 'player_id' IS NULL
                           OR NOT EXISTS (
                                SELECT 1 FROM players player
                                WHERE player.player_id::TEXT = operation.result -> 'result' ->> 'player_id'
                              )
                           OR NOT EXISTS (
                                SELECT 1 FROM item_instances item
                                WHERE item.item_instance_id::TEXT = operation.result -> 'result' ->> 'item_instance_id'
                              )
                           OR (operation.result -> 'result' ->> 'item_state_version' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                           OR (operation.result -> 'result' ->> 'item_state_version')::NUMERIC
                                <> (operation.result -> 'request' ->> 'expected_item_state_version')::NUMERIC + 1
                           OR (operation.result -> 'result' ->> 'player_state_version' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                         WHEN 'CLAN_STORAGE_UNIQUE_WITHDRAW' THEN
                              operation.result -> 'request' ->> 'item_instance_id' IS NULL
                           OR operation.result -> 'request' ->> 'player_id' IS NULL
                           OR (operation.result -> 'request' ->> 'expected_item_state_version' ~ '^[0-9]+$') IS DISTINCT FROM TRUE
                           OR NOT EXISTS (
                                SELECT 1 FROM players player
                                WHERE player.player_id::TEXT = operation.result -> 'request' ->> 'player_id'
                              )
                           OR operation.result -> 'result' ->> 'clan_id'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'clan_id'
                           OR operation.result -> 'result' ->> 'player_id'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'player_id'
                           OR operation.result -> 'result' ->> 'item_instance_id'
                                IS DISTINCT FROM operation.result -> 'request' ->> 'item_instance_id'
                           OR NOT EXISTS (
                                SELECT 1 FROM item_instances item
                                WHERE item.item_instance_id::TEXT = operation.result -> 'result' ->> 'item_instance_id'
                              )
                           OR (operation.result -> 'result' ->> 'item_state_version' ~ '^[1-9][0-9]*$') IS DISTINCT FROM TRUE
                           OR (operation.result -> 'result' ->> 'item_state_version')::NUMERIC
                                <> (operation.result -> 'request' ->> 'expected_item_state_version')::NUMERIC + 1
                           OR operation.result -> 'result' ->> 'delivery_id' IS NULL
                         ELSE TRUE
                       END
                  )
                ORDER BY operation.operation_id
                LIMIT ?
                """)) {
            bindTypes(statement, 1);
            statement.setInt(5, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_STORAGE_OPERATION_EVIDENCE_MISMATCH",
                            operationId.toString(),
                            "Processed clan storage operation has an invalid frozen request/result shape for "
                                    + rows.getString("operation_type")
                    ));
                }
            }
        }
    }

    private static void verifyCommodityHistory(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH raw AS (
                    SELECT operation_id,
                           operation_type,
                           result -> 'request' ->> 'clan_id' AS clan_id_text,
                           result -> 'request' ->> 'commodity_definition_id' AS commodity_definition_id,
                           CASE WHEN result -> 'request' ->> 'quantity' ~ '^[1-9][0-9]*$'
                                THEN (result -> 'request' ->> 'quantity')::NUMERIC END AS amount,
                           result -> 'result' -> 'storage' ->> 'clan_id' AS result_clan_id_text,
                           result -> 'result' -> 'storage' ->> 'commodity_definition_id' AS result_commodity_definition_id,
                           CASE WHEN result -> 'result' -> 'storage' ->> 'quantity' ~ '^[0-9]+$'
                                THEN (result -> 'result' -> 'storage' ->> 'quantity')::NUMERIC END AS result_quantity,
                           CASE WHEN result -> 'result' -> 'storage' ->> 'state_version' ~ '^[1-9][0-9]*$'
                                THEN (result -> 'result' -> 'storage' ->> 'state_version')::NUMERIC END AS result_version
                    FROM processed_operations
                    WHERE operation_type IN (?, ?)
                ),
                tagged AS (
                    SELECT raw.*,
                           clan_id_text IS NOT NULL
                           AND commodity_definition_id IS NOT NULL
                           AND amount IS NOT NULL
                           AND result_quantity IS NOT NULL
                           AND result_version IS NOT NULL
                           AND result_clan_id_text = clan_id_text
                           AND result_commodity_definition_id = commodity_definition_id AS valid
                    FROM raw
                ),
                invalid_keys AS (
                    SELECT DISTINCT clan_id_text, commodity_definition_id
                    FROM tagged
                    WHERE clan_id_text IS NOT NULL
                      AND commodity_definition_id IS NOT NULL
                      AND NOT valid
                ),
                history AS (
                    SELECT tagged.*,
                           LAG(result_quantity) OVER (
                               PARTITION BY clan_id_text, commodity_definition_id
                               ORDER BY result_version, operation_id
                           ) AS previous_quantity
                    FROM tagged
                    WHERE valid
                      AND NOT EXISTS (
                          SELECT 1 FROM invalid_keys invalid
                          WHERE invalid.clan_id_text = tagged.clan_id_text
                            AND invalid.commodity_definition_id = tagged.commodity_definition_id
                      )
                ),
                summary AS (
                    SELECT clan_id_text,
                           commodity_definition_id,
                           COUNT(*)::NUMERIC AS operation_count,
                           COUNT(DISTINCT result_version)::NUMERIC AS distinct_version_count,
                           MIN(result_version) AS minimum_version,
                           MAX(result_version) AS maximum_version
                    FROM history
                    GROUP BY clan_id_text, commodity_definition_id
                ),
                latest AS (
                    SELECT DISTINCT ON (clan_id_text, commodity_definition_id)
                           clan_id_text, commodity_definition_id, result_quantity, result_version
                    FROM history
                    ORDER BY clan_id_text, commodity_definition_id, result_version DESC, operation_id DESC
                ),
                broken AS (
                    SELECT DISTINCT clan_id_text, commodity_definition_id
                    FROM history
                    WHERE CASE operation_type
                            WHEN 'CLAN_STORAGE_COMMODITY_DEPOSIT' THEN
                                result_quantity <> COALESCE(previous_quantity, 0::NUMERIC) + amount
                            WHEN 'CLAN_STORAGE_COMMODITY_WITHDRAW' THEN
                                result_quantity + amount <> COALESCE(previous_quantity, 0::NUMERIC)
                            ELSE TRUE
                          END
                ),
                current_state AS (
                    SELECT clan_id::TEXT AS clan_id_text,
                           commodity_definition_id,
                           quantity::NUMERIC AS quantity,
                           state_version::NUMERIC AS state_version
                    FROM clan_commodity_balances
                )
                SELECT COALESCE(current_state.clan_id_text, summary.clan_id_text) AS clan_id_text,
                       COALESCE(current_state.commodity_definition_id, summary.commodity_definition_id)
                           AS commodity_definition_id
                FROM current_state
                FULL OUTER JOIN summary
                  ON summary.clan_id_text = current_state.clan_id_text
                 AND summary.commodity_definition_id = current_state.commodity_definition_id
                LEFT JOIN latest
                  ON latest.clan_id_text = summary.clan_id_text
                 AND latest.commodity_definition_id = summary.commodity_definition_id
                LEFT JOIN broken
                  ON broken.clan_id_text = summary.clan_id_text
                 AND broken.commodity_definition_id = summary.commodity_definition_id
                WHERE NOT EXISTS (
                          SELECT 1 FROM invalid_keys invalid
                          WHERE invalid.clan_id_text = COALESCE(current_state.clan_id_text, summary.clan_id_text)
                            AND invalid.commodity_definition_id = COALESCE(
                                current_state.commodity_definition_id, summary.commodity_definition_id
                            )
                      )
                  AND (
                       current_state.clan_id_text IS NULL
                    OR summary.clan_id_text IS NULL
                    OR summary.operation_count IS DISTINCT FROM summary.distinct_version_count
                    OR summary.minimum_version IS DISTINCT FROM 1::NUMERIC
                    OR summary.maximum_version IS DISTINCT FROM summary.operation_count
                    OR current_state.state_version IS DISTINCT FROM summary.maximum_version
                    OR current_state.quantity IS DISTINCT FROM latest.result_quantity
                    OR broken.clan_id_text IS NOT NULL
                  )
                ORDER BY 1, 2
                LIMIT ?
                """)) {
            statement.setString(1, COMMODITY_DEPOSIT);
            statement.setString(2, COMMODITY_WITHDRAW);
            statement.setInt(3, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String subject = rows.getString("clan_id_text") + ":" + rows.getString("commodity_definition_id");
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_STORAGE_COMMODITY_HISTORY_MISMATCH",
                            subject,
                            "Clan commodity storage quantity/version does not reconcile with contiguous deposit/withdraw arithmetic history"
                    ));
                }
            }
        }
    }

    private static void verifyWithdrawalDeliveries(
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
                  AND jsonb_typeof(operation.result -> 'request') = 'object'
                  AND jsonb_typeof(operation.result -> 'result') = 'object'
                  AND (
                       (
                         operation.operation_type = ?
                         AND (
                              operation.result -> 'request' ->> 'quantity' ~ '^[1-9][0-9]*$'
                              AND operation.result -> 'result' ->> 'withdrawn_quantity'
                                   = operation.result -> 'request' ->> 'quantity'
                              AND operation.result -> 'result' ->> 'player_id'
                                   = operation.result -> 'request' ->> 'player_id'
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM pending_commodity_deliveries delivery
                                  WHERE delivery.delivery_id::TEXT = operation.result -> 'result' ->> 'delivery_id'
                                    AND delivery.player_id::TEXT = operation.result -> 'result' ->> 'player_id'
                                    AND delivery.commodity_definition_id
                                         = operation.result -> 'request' ->> 'commodity_definition_id'
                                    AND delivery.quantity::TEXT = operation.result -> 'request' ->> 'quantity'
                                    AND delivery.source_operation_id = operation.operation_id
                              )
                         )
                       )
                    OR (
                         operation.operation_type = ?
                         AND operation.result -> 'result' ->> 'player_id'
                              = operation.result -> 'request' ->> 'player_id'
                         AND operation.result -> 'result' ->> 'item_instance_id'
                              = operation.result -> 'request' ->> 'item_instance_id'
                         AND NOT EXISTS (
                             SELECT 1
                             FROM pending_unique_deliveries delivery
                             WHERE delivery.delivery_id::TEXT = operation.result -> 'result' ->> 'delivery_id'
                               AND delivery.recipient_player_id::TEXT = operation.result -> 'result' ->> 'player_id'
                               AND delivery.item_instance_id::TEXT = operation.result -> 'result' ->> 'item_instance_id'
                               AND delivery.issue_operation_id = operation.operation_id
                               AND delivery.issue_reason = operation.result -> 'request' ->> 'reason'
                         )
                       )
                  )
                ORDER BY operation.operation_id
                LIMIT ?
                """)) {
            statement.setString(1, COMMODITY_WITHDRAW);
            statement.setString(2, UNIQUE_WITHDRAW);
            statement.setString(3, COMMODITY_WITHDRAW);
            statement.setString(4, UNIQUE_WITHDRAW);
            statement.setInt(5, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_STORAGE_WITHDRAWAL_DELIVERY_MISMATCH",
                            operationId.toString(),
                            "Clan storage withdrawal does not retain the exact durable pending-delivery issuance for "
                                    + rows.getString("operation_type")
                    ));
                }
            }
        }
    }

    private static void verifyUniqueProvenance(
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
                  AND jsonb_typeof(operation.result -> 'request') = 'object'
                  AND jsonb_typeof(operation.result -> 'result') = 'object'
                  AND operation.result -> 'result' ->> 'item_instance_id'
                       = operation.result -> 'request' ->> 'item_instance_id'
                  AND operation.result -> 'result' ->> 'player_id' IS NOT NULL
                  AND operation.result -> 'result' ->> 'item_state_version' ~ '^[1-9][0-9]*$'
                  AND (
                       SELECT COUNT(*)
                       FROM item_provenance provenance
                       WHERE provenance.item_instance_id::TEXT
                                  = operation.result -> 'result' ->> 'item_instance_id'
                         AND provenance.sequence_no::TEXT
                                  = operation.result -> 'result' ->> 'item_state_version'
                         AND provenance.operation_id = operation.operation_id
                         AND provenance.event_type = 'MOVED'
                         AND provenance.reason = operation.result -> 'request' ->> 'reason'
                         AND provenance.actor_player_id::TEXT
                                  = operation.result -> 'result' ->> 'player_id'
                         AND provenance.from_location_kind = CASE operation.operation_type
                               WHEN 'CLAN_STORAGE_UNIQUE_DEPOSIT' THEN 'PLAYER_INVENTORY'
                               ELSE 'CLAN_STORAGE'
                             END
                         AND provenance.from_location_id::TEXT = CASE operation.operation_type
                               WHEN 'CLAN_STORAGE_UNIQUE_DEPOSIT' THEN operation.result -> 'result' ->> 'player_id'
                               ELSE operation.result -> 'request' ->> 'clan_id'
                             END
                         AND provenance.to_location_kind = CASE operation.operation_type
                               WHEN 'CLAN_STORAGE_UNIQUE_DEPOSIT' THEN 'CLAN_STORAGE'
                               ELSE 'PENDING_DELIVERY'
                             END
                         AND provenance.to_location_id::TEXT = CASE operation.operation_type
                               WHEN 'CLAN_STORAGE_UNIQUE_DEPOSIT' THEN operation.result -> 'request' ->> 'clan_id'
                               ELSE operation.result -> 'result' ->> 'delivery_id'
                             END
                      ) <> 1
                ORDER BY operation.operation_id
                LIMIT ?
                """)) {
            statement.setString(1, UNIQUE_DEPOSIT);
            statement.setString(2, UNIQUE_WITHDRAW);
            statement.setInt(3, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_STORAGE_UNIQUE_PROVENANCE_MISMATCH",
                            operationId.toString(),
                            "Clan unique-item storage movement does not retain its exact historical provenance for "
                                    + rows.getString("operation_type")
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
                WITH operations AS (
                    SELECT operation_id,
                           operation_type,
                           result -> 'request' ->> 'clan_id' AS clan_id_text,
                           COALESCE(
                               result -> 'result' ->> 'player_id',
                               result -> 'request' ->> 'player_id'
                           ) AS player_id_text,
                           CASE
                             WHEN operation_type IN ('CLAN_STORAGE_COMMODITY_DEPOSIT', 'CLAN_STORAGE_COMMODITY_WITHDRAW')
                             THEN 'COMMODITY'
                             ELSE 'ITEM_INSTANCE'
                           END AS asset_type,
                           CASE
                             WHEN operation_type IN ('CLAN_STORAGE_COMMODITY_DEPOSIT', 'CLAN_STORAGE_COMMODITY_WITHDRAW')
                             THEN result -> 'request' ->> 'commodity_definition_id'
                             ELSE result -> 'request' ->> 'item_instance_id'
                           END AS asset_id,
                           CASE
                             WHEN operation_type IN ('CLAN_STORAGE_COMMODITY_DEPOSIT', 'CLAN_STORAGE_COMMODITY_WITHDRAW')
                             THEN result -> 'request' ->> 'quantity'
                             ELSE '1'
                           END AS amount_text,
                           result -> 'request' ->> 'reason' AS reason
                    FROM processed_operations
                    WHERE operation_type IN (?, ?, ?, ?)
                      AND jsonb_typeof(result -> 'request') = 'object'
                      AND jsonb_typeof(result -> 'result') = 'object'
                )
                SELECT operation_id, operation_type
                FROM operations operation
                WHERE clan_id_text IS NOT NULL
                  AND player_id_text IS NOT NULL
                  AND asset_id IS NOT NULL
                  AND amount_text ~ '^[1-9][0-9]*$'
                  AND reason IS NOT NULL
                  AND BTRIM(reason) <> ''
                  AND (
                       (SELECT COUNT(*) FROM economic_ledger ledger
                        WHERE ledger.operation_id = operation.operation_id) <> 2
                    OR (SELECT COUNT(*) FROM economic_ledger ledger
                        WHERE ledger.operation_id = operation.operation_id
                          AND ledger.line_no = 0
                          AND ledger.asset_type = operation.asset_type
                          AND ledger.asset_id = operation.asset_id
                          AND ledger.amount::TEXT = operation.amount_text
                          AND ledger.reason = operation.reason
                          AND ledger.related_entity_id = operation.clan_id_text
                          AND ledger.direction = CASE
                                WHEN operation.operation_type IN (
                                    'CLAN_STORAGE_COMMODITY_DEPOSIT', 'CLAN_STORAGE_UNIQUE_DEPOSIT'
                                ) THEN 'DEBIT' ELSE 'DEBIT'
                              END
                          AND (
                               (operation.operation_type IN (
                                    'CLAN_STORAGE_COMMODITY_DEPOSIT', 'CLAN_STORAGE_UNIQUE_DEPOSIT'
                                ) AND ledger.player_id::TEXT = operation.player_id_text)
                            OR (operation.operation_type IN (
                                    'CLAN_STORAGE_COMMODITY_WITHDRAW', 'CLAN_STORAGE_UNIQUE_WITHDRAW'
                                ) AND ledger.player_id IS NULL)
                          )) <> 1
                    OR (SELECT COUNT(*) FROM economic_ledger ledger
                        WHERE ledger.operation_id = operation.operation_id
                          AND ledger.line_no = 1
                          AND ledger.asset_type = operation.asset_type
                          AND ledger.asset_id = operation.asset_id
                          AND ledger.amount::TEXT = operation.amount_text
                          AND ledger.reason = operation.reason
                          AND ledger.related_entity_id = operation.clan_id_text
                          AND ledger.direction = 'CREDIT'
                          AND (
                               (operation.operation_type IN (
                                    'CLAN_STORAGE_COMMODITY_DEPOSIT', 'CLAN_STORAGE_UNIQUE_DEPOSIT'
                                ) AND ledger.player_id IS NULL)
                            OR (operation.operation_type IN (
                                    'CLAN_STORAGE_COMMODITY_WITHDRAW', 'CLAN_STORAGE_UNIQUE_WITHDRAW'
                                ) AND ledger.player_id::TEXT = operation.player_id_text)
                          )) <> 1
                  )
                ORDER BY operation_id
                LIMIT ?
                """)) {
            bindTypes(statement, 1);
            statement.setInt(5, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_STORAGE_LEDGER_EVIDENCE_MISMATCH",
                            operationId.toString(),
                            "Clan storage operation does not retain its exact two-line player↔clan asset ledger evidence for "
                                    + rows.getString("operation_type")
                    ));
                }
            }
        }
    }

    private static void bindTypes(PreparedStatement statement, int start) throws SQLException {
        statement.setString(start, COMMODITY_DEPOSIT);
        statement.setString(start + 1, COMMODITY_WITHDRAW);
        statement.setString(start + 2, UNIQUE_DEPOSIT);
        statement.setString(start + 3, UNIQUE_WITHDRAW);
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
