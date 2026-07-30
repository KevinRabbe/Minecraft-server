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

/** Read-only bounded reconciliation for Bounty contract, progress, summon, materialization, and terminal evidence. */
public final class BountyLifecycleIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public BountyLifecycleIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyContractStartEvidence(connection, issues, maxIssues);
            verifyDirectProgressEvidence(connection, issues, maxIssues);
            verifyManagedKillEvidence(connection, issues, maxIssues);
            verifySummonStatePairing(connection, issues, maxIssues);
            verifySummonPrepareEvidence(connection, issues, maxIssues);
            verifySummonLeaseOperationEvidence(connection, issues, maxIssues);
            verifyBossMaterializationEvidence(connection, issues, maxIssues);
            verifyTerminalEvidence(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyContractStartEvidence(Connection connection, List<IntegrityIssue> issues, int maxIssues)
            throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH start_evidence AS (
                    SELECT c.contract_id, c.player_id, c.family_id, c.tier, c.content_version,
                           c.required_eligible_kills, c.fee_operation_id, op.operation_type, op.result,
                           COALESCE((SELECT COUNT(*) FROM economic_ledger l
                                     WHERE l.operation_id = c.fee_operation_id), 0) AS ledger_count,
                           COALESCE((SELECT COUNT(*) FROM economic_ledger l
                                     WHERE l.operation_id = c.fee_operation_id
                                       AND l.line_no = 0
                                       AND l.player_id = c.player_id
                                       AND l.asset_type = 'CURRENCY'
                                       AND l.asset_id = 'coin'
                                       AND l.direction = 'DEBIT'
                                       AND l.amount::text = op.result ->> 'fee_minor'
                                       AND l.reason = op.result ->> 'reason'), 0) AS matching_fee_ledger_count
                    FROM bounty_contracts c
                    LEFT JOIN processed_operations op ON op.operation_id = c.fee_operation_id
                ), mismatches AS (
                    SELECT contract_id
                    FROM start_evidence
                    WHERE operation_type IS DISTINCT FROM 'BOUNTY_CONTRACT_START'
                       OR result ->> 'request_player_id' IS DISTINCT FROM player_id::text
                       OR result ->> 'request_family_id' IS DISTINCT FROM family_id
                       OR result ->> 'request_tier' IS DISTINCT FROM tier::text
                       OR result ->> 'reason' IS NULL
                       OR result ->> 'reason' !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                       OR jsonb_typeof(result -> 'fee_minor') IS DISTINCT FROM 'number'
                       OR CASE WHEN jsonb_typeof(result -> 'fee_minor') = 'number'
                               THEN (result ->> 'fee_minor')::numeric < 0 ELSE FALSE END
                       OR result #>> '{contract,contract_id}' IS DISTINCT FROM contract_id::text
                       OR result #>> '{contract,player_id}' IS DISTINCT FROM player_id::text
                       OR result #>> '{contract,family_id}' IS DISTINCT FROM family_id
                       OR result #>> '{contract,tier}' IS DISTINCT FROM tier::text
                       OR result #>> '{contract,content_version}' IS DISTINCT FROM content_version::text
                       OR result #>> '{contract,status}' IS DISTINCT FROM 'ACTIVE_HUNT'
                       OR result #>> '{contract,eligible_kill_progress}' IS DISTINCT FROM '0'
                       OR result #>> '{contract,summon_authorizations_remaining}' IS DISTINCT FROM '0'
                       OR result #>> '{contract,state_version}' IS DISTINCT FROM '0'
                       OR result #>> '{contract,required_eligible_kills}' IS DISTINCT FROM required_eligible_kills::text
                       OR CASE
                            WHEN jsonb_typeof(result -> 'fee_minor') = 'number'
                                 AND (result ->> 'fee_minor')::numeric = 0
                            THEN ledger_count <> 0
                            WHEN jsonb_typeof(result -> 'fee_minor') = 'number'
                                 AND (result ->> 'fee_minor')::numeric > 0
                            THEN ledger_count <> 1 OR matching_fee_ledger_count <> 1
                            ELSE FALSE
                          END
                )
                SELECT contract_id FROM mismatches ORDER BY contract_id LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID contractId = rows.getObject("contract_id", UUID.class);
                    issues.add(new IntegrityIssue(IntegritySeverity.CRITICAL,
                            "BOUNTY_CONTRACT_START_EVIDENCE_MISMATCH", contractId.toString(),
                            "Bounty contract does not reconcile to its exact start request, frozen content version, and Coin-fee evidence"));
                }
            }
        }
    }

    private static void verifyDirectProgressEvidence(Connection connection, List<IntegrityIssue> issues, int maxIssues)
            throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT op.operation_id
                FROM processed_operations op
                LEFT JOIN bounty_contracts c
                  ON c.contract_id::text = op.result ->> 'request_contract_id'
                WHERE op.operation_type = 'BOUNTY_KILL_PROGRESS'
                  AND (
                    c.contract_id IS NULL
                    OR op.result ->> 'request_player_id' IS DISTINCT FROM c.player_id::text
                    OR op.result ->> 'eligible_kills' IS NULL
                    OR op.result ->> 'reason' IS NULL
                    OR op.result ->> 'reason' !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                    OR op.result #>> '{contract,contract_id}' IS DISTINCT FROM c.contract_id::text
                    OR op.result #>> '{contract,player_id}' IS DISTINCT FROM c.player_id::text
                    OR op.result #>> '{contract,family_id}' IS DISTINCT FROM c.family_id
                    OR op.result #>> '{contract,tier}' IS DISTINCT FROM c.tier::text
                    OR op.result #>> '{contract,content_version}' IS DISTINCT FROM c.content_version::text
                    OR CASE
                        WHEN jsonb_typeof(op.result #> '{contract,state_version}') = 'number'
                        THEN (op.result #>> '{contract,state_version}')::numeric > c.state_version::numeric
                        ELSE TRUE
                       END
                    OR CASE
                        WHEN jsonb_typeof(op.result #> '{contract,eligible_kill_progress}') = 'number'
                        THEN (op.result #>> '{contract,eligible_kill_progress}')::numeric > c.eligible_kill_progress::numeric
                        ELSE TRUE
                       END
                  )
                ORDER BY op.operation_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(IntegritySeverity.CRITICAL,
                            "BOUNTY_PROGRESS_EVIDENCE_MISMATCH", operationId.toString(),
                            "Bounty progress operation does not reconcile to its contract identity, frozen content version, and monotonic history"));
                }
            }
        }
    }

    private static void verifyManagedKillEvidence(Connection connection, List<IntegrityIssue> issues, int maxIssues)
            throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH mismatches AS (
                    SELECT b.progress_operation_id
                    FROM bounty_managed_kill_progress b
                    LEFT JOIN resource_harvests h ON h.operation_id = b.resource_kill_operation_id
                    LEFT JOIN resource_entity_kill_claims k ON k.operation_id = b.resource_kill_operation_id
                    LEFT JOIN resource_sources s ON s.source_id = b.source_id
                    LEFT JOIN processed_operations op ON op.operation_id = b.progress_operation_id
                    LEFT JOIN bounty_contracts c ON c.contract_id = b.contract_id
                    WHERE h.harvest_id IS NULL OR k.operation_id IS NULL
                       OR h.player_id IS DISTINCT FROM b.player_id OR h.source_id IS DISTINCT FROM b.source_id
                       OR s.definition_id IS DISTINCT FROM b.source_definition_id
                       OR op.operation_type IS DISTINCT FROM 'BOUNTY_MANAGED_KILL_PROGRESS'
                       OR op.result ->> 'resource_kill_operation_id' IS DISTINCT FROM b.resource_kill_operation_id::text
                       OR op.result ->> 'player_id' IS DISTINCT FROM b.player_id::text
                       OR op.result ->> 'source_definition_id' IS DISTINCT FROM b.source_definition_id
                       OR op.result ->> 'family_id' IS DISTINCT FROM b.family_id
                       OR op.result ->> 'eligible_kills' IS DISTINCT FROM b.eligible_kills::text
                       OR op.result ->> 'reason' IS NULL
                       OR op.result ->> 'reason' !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                       OR op.result ->> 'applied' IS DISTINCT FROM CASE WHEN b.contract_id IS NULL THEN 'false' ELSE 'true' END
                       OR (b.contract_id IS NULL AND op.result #>> '{contract,contract_id}' IS NOT NULL)
                       OR (b.contract_id IS NOT NULL AND (
                            c.contract_id IS NULL OR c.player_id IS DISTINCT FROM b.player_id OR c.family_id IS DISTINCT FROM b.family_id
                            OR op.result #>> '{contract,contract_id}' IS DISTINCT FROM b.contract_id::text
                            OR op.result #>> '{contract,player_id}' IS DISTINCT FROM b.player_id::text
                            OR op.result #>> '{contract,family_id}' IS DISTINCT FROM b.family_id
                            OR op.result #>> '{contract,tier}' IS DISTINCT FROM c.tier::text
                            OR op.result #>> '{contract,content_version}' IS DISTINCT FROM c.content_version::text
                            OR CASE
                                WHEN jsonb_typeof(op.result #> '{contract,state_version}') = 'number'
                                THEN (op.result #>> '{contract,state_version}')::numeric > c.state_version::numeric
                                ELSE TRUE
                               END
                       ))
                    UNION
                    SELECT op.operation_id
                    FROM processed_operations op
                    LEFT JOIN bounty_managed_kill_progress b ON b.progress_operation_id = op.operation_id
                    WHERE op.operation_type = 'BOUNTY_MANAGED_KILL_PROGRESS' AND b.progress_operation_id IS NULL
                )
                SELECT progress_operation_id FROM mismatches ORDER BY progress_operation_id LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("progress_operation_id", UUID.class);
                    issues.add(new IntegrityIssue(IntegritySeverity.CRITICAL,
                            "BOUNTY_MANAGED_KILL_EVIDENCE_MISMATCH", operationId.toString(),
                            "Managed Bounty kill classification does not match its resource harvest, frozen contract content, bridge, or processed result"));
                }
            }
        }
    }

    private static void verifySummonStatePairing(Connection connection, List<IntegrityIssue> issues, int maxIssues)
            throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH mismatches AS (
                    SELECT c.contract_id::text AS subject_id
                    FROM bounty_contracts c
                    LEFT JOIN bounty_summons s ON s.contract_id = c.contract_id
                    WHERE (c.status IN ('ACTIVE_HUNT', 'SUMMON_READY') AND s.summon_id IS NOT NULL)
                       OR (c.status = 'SUMMONED' AND (s.summon_id IS NULL OR s.status NOT IN ('READY', 'ACTIVE')))
                       OR (c.status = 'COMPLETED' AND (s.summon_id IS NULL OR s.status IS DISTINCT FROM 'DEFEATED'))
                       OR (c.status = 'FAILED' AND (s.summon_id IS NULL OR s.status IS DISTINCT FROM 'FAILED'))
                       OR (c.status = 'COMPLETED' AND c.reward_operation_id IS NULL)
                       OR (c.status <> 'COMPLETED' AND c.reward_operation_id IS NOT NULL)
                    UNION
                    SELECT s.summon_id::text
                    FROM bounty_summons s
                    LEFT JOIN bounty_contracts c ON c.contract_id = s.contract_id
                    WHERE c.contract_id IS NULL
                       OR (s.status IN ('READY', 'ACTIVE') AND c.status IS DISTINCT FROM 'SUMMONED')
                       OR (s.status = 'DEFEATED' AND c.status IS DISTINCT FROM 'COMPLETED')
                       OR (s.status = 'FAILED' AND c.status IS DISTINCT FROM 'FAILED')
                )
                SELECT subject_id FROM mismatches ORDER BY subject_id LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String subjectId = rows.getString("subject_id");
                    issues.add(new IntegrityIssue(IntegritySeverity.CRITICAL,
                            "BOUNTY_SUMMON_STATE_MISMATCH", subjectId,
                            "Bounty contract and summon terminal/live states do not form one coherent lifecycle"));
                }
            }
        }
    }

    private static void verifySummonPrepareEvidence(Connection connection, List<IntegrityIssue> issues, int maxIssues)
            throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH evidence AS (
                    SELECT s.summon_id, s.contract_id, c.player_id, c.family_id, c.tier, c.content_version,
                           COUNT(op.operation_id) AS prepare_count,
                           COUNT(op.operation_id) FILTER (
                               WHERE op.result ->> 'request_contract_id' = s.contract_id::text
                                 AND op.result ->> 'request_player_id' = c.player_id::text
                                 AND op.result ->> 'reason' ~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                                 AND op.result ->> 'boss_definition_id' ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
                                 AND op.result #>> '{summon,summon_id}' = s.summon_id::text
                                 AND op.result #>> '{summon,contract_id}' = s.contract_id::text
                                 AND op.result #>> '{summon,status}' = 'READY'
                                 AND op.result #>> '{summon,state_version}' = '0'
                                 AND op.result #>> '{contract,contract_id}' = c.contract_id::text
                                 AND op.result #>> '{contract,player_id}' = c.player_id::text
                                 AND op.result #>> '{contract,family_id}' = c.family_id
                                 AND op.result #>> '{contract,tier}' = c.tier::text
                                 AND op.result #>> '{contract,content_version}' = c.content_version::text
                                 AND op.result #>> '{contract,status}' = 'SUMMONED'
                           ) AS matching_count
                    FROM bounty_summons s
                    JOIN bounty_contracts c ON c.contract_id = s.contract_id
                    LEFT JOIN processed_operations op
                      ON op.operation_type = 'BOUNTY_SUMMON_PREPARE'
                     AND op.result #>> '{summon,summon_id}' = s.summon_id::text
                    GROUP BY s.summon_id, s.contract_id, c.player_id, c.family_id, c.tier, c.content_version
                )
                SELECT summon_id FROM evidence WHERE prepare_count <> 1 OR matching_count <> 1
                ORDER BY summon_id LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID summonId = rows.getObject("summon_id", UUID.class);
                    issues.add(new IntegrityIssue(IntegritySeverity.CRITICAL,
                            "BOUNTY_SUMMON_PREPARE_EVIDENCE_MISMATCH", summonId.toString(),
                            "Bounty summon does not reconcile to one exact historical prepare result/content version"));
                }
            }
        }
    }

    private static void verifySummonLeaseOperationEvidence(Connection connection, List<IntegrityIssue> issues, int maxIssues)
            throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT op.operation_id
                FROM processed_operations op
                LEFT JOIN bounty_summons s ON s.summon_id::text = op.result ->> 'request_summon_id'
                WHERE op.operation_type IN ('BOUNTY_SUMMON_CLAIM', 'BOUNTY_SUMMON_HEARTBEAT')
                  AND (
                    s.summon_id IS NULL
                    OR op.result ->> 'request_backend_id' IS NULL
                    OR op.result ->> 'reason' IS NULL
                    OR op.result ->> 'reason' !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                    OR op.result ->> 'boss_definition_id' !~ '^[a-z0-9][a-z0-9._-]{0,63}$'
                    OR op.result #>> '{summon,summon_id}' IS DISTINCT FROM s.summon_id::text
                    OR op.result #>> '{summon,contract_id}' IS DISTINCT FROM s.contract_id::text
                    OR op.result #>> '{summon,status}' IS DISTINCT FROM 'ACTIVE'
                    OR op.result #>> '{summon,owner_backend_id}' IS DISTINCT FROM op.result ->> 'request_backend_id'
                    OR CASE
                        WHEN jsonb_typeof(op.result #> '{summon,state_version}') = 'number'
                        THEN (op.result #>> '{summon,state_version}')::numeric > s.state_version::numeric
                        ELSE TRUE
                       END
                    OR (op.operation_type = 'BOUNTY_SUMMON_HEARTBEAT' AND CASE
                        WHEN jsonb_typeof(op.result -> 'expected_summon_state_version') = 'number'
                         AND jsonb_typeof(op.result #> '{summon,state_version}') = 'number'
                        THEN (op.result ->> 'expected_summon_state_version')::numeric + 1
                             <> (op.result #>> '{summon,state_version}')::numeric
                        ELSE TRUE
                       END)
                  )
                ORDER BY op.operation_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(IntegritySeverity.CRITICAL,
                            "BOUNTY_SUMMON_LEASE_EVIDENCE_MISMATCH", operationId.toString(),
                            "Bounty summon claim/heartbeat history does not reconcile to the referenced summon"));
                }
            }
        }
    }

    private static void verifyBossMaterializationEvidence(Connection connection, List<IntegrityIssue> issues, int maxIssues)
            throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH evidence AS (
                    SELECT m.summon_id, s.activated_at,
                           COUNT(op.operation_id) AS operation_count,
                           COUNT(op.operation_id) FILTER (
                               WHERE op.result ->> 'summon_id' = m.summon_id::text
                                 AND op.result ->> 'backend_id' = m.backend_id
                                 AND op.result ->> 'boss_definition_id' = m.boss_definition_id
                                 AND op.result ->> 'entity_uuid' = m.entity_uuid::text
                                 AND op.result ->> 'world_name' = m.world_name
                                 AND op.result #>> '{materialization,summon_id}' = m.summon_id::text
                                 AND op.result #>> '{materialization,backend_id}' = m.backend_id
                                 AND op.result #>> '{materialization,boss_definition_id}' = m.boss_definition_id
                                 AND op.result #>> '{materialization,entity_uuid}' = m.entity_uuid::text
                                 AND op.result #>> '{materialization,world_name}' = m.world_name
                                 AND CASE
                                    WHEN jsonb_typeof(op.result -> 'spawn_x') = 'number'
                                     AND jsonb_typeof(op.result -> 'spawn_y') = 'number'
                                     AND jsonb_typeof(op.result -> 'spawn_z') = 'number'
                                     AND jsonb_typeof(op.result #> '{materialization,spawn_x}') = 'number'
                                     AND jsonb_typeof(op.result #> '{materialization,spawn_y}') = 'number'
                                     AND jsonb_typeof(op.result #> '{materialization,spawn_z}') = 'number'
                                    THEN (op.result ->> 'spawn_x')::double precision = m.spawn_x
                                     AND (op.result ->> 'spawn_y')::double precision = m.spawn_y
                                     AND (op.result ->> 'spawn_z')::double precision = m.spawn_z
                                     AND (op.result #>> '{materialization,spawn_x}')::double precision = m.spawn_x
                                     AND (op.result #>> '{materialization,spawn_y}')::double precision = m.spawn_y
                                     AND (op.result #>> '{materialization,spawn_z}')::double precision = m.spawn_z
                                    ELSE FALSE END
                           ) AS matching_count
                    FROM bounty_boss_materializations m
                    LEFT JOIN bounty_summons s ON s.summon_id = m.summon_id
                    LEFT JOIN processed_operations op
                      ON op.operation_type = 'BOUNTY_BOSS_MATERIALIZE'
                     AND op.result #>> '{materialization,summon_id}' = m.summon_id::text
                    GROUP BY m.summon_id, s.activated_at
                )
                SELECT summon_id FROM evidence
                WHERE activated_at IS NULL OR operation_count <> 1 OR matching_count <> 1
                ORDER BY summon_id LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID summonId = rows.getObject("summon_id", UUID.class);
                    issues.add(new IntegrityIssue(IntegritySeverity.CRITICAL,
                            "BOUNTY_BOSS_MATERIALIZATION_EVIDENCE_MISMATCH", summonId.toString(),
                            "Bounty boss materialization does not reconcile to its exact immutable operation evidence"));
                }
            }
        }
    }

    private static void verifyTerminalEvidence(Connection connection, List<IntegrityIssue> issues, int maxIssues)
            throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH terminal AS (
                    SELECT c.contract_id, c.player_id, c.family_id, c.tier, c.content_version,
                           c.status AS contract_status, c.state_version AS contract_state_version,
                           c.reward_operation_id, s.summon_id, s.status AS summon_status,
                           s.owner_backend_id, s.state_version AS summon_state_version,
                           CASE WHEN c.status = 'COMPLETED' THEN 'BOUNTY_BOSS_COMPLETE' ELSE 'BOUNTY_BOSS_FAIL' END AS expected_type
                    FROM bounty_contracts c
                    JOIN bounty_summons s ON s.contract_id = c.contract_id
                    WHERE c.status IN ('COMPLETED', 'FAILED')
                ), evidence AS (
                    SELECT t.*,
                           COUNT(op.operation_id) AS terminal_count,
                           COUNT(op.operation_id) FILTER (
                               WHERE op.result ->> 'request_summon_id' = t.summon_id::text
                                 AND op.result ->> 'request_backend_id' = t.owner_backend_id
                                 AND op.result ->> 'reason' ~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                                 AND op.result #>> '{contract,contract_id}' = t.contract_id::text
                                 AND op.result #>> '{contract,player_id}' = t.player_id::text
                                 AND op.result #>> '{contract,family_id}' = t.family_id
                                 AND op.result #>> '{contract,tier}' = t.tier::text
                                 AND op.result #>> '{contract,content_version}' = t.content_version::text
                                 AND op.result #>> '{contract,status}' = t.contract_status
                                 AND op.result #>> '{contract,state_version}' = t.contract_state_version::text
                                 AND CASE
                                    WHEN jsonb_typeof(op.result -> 'expected_summon_state_version') = 'number'
                                    THEN (op.result ->> 'expected_summon_state_version')::numeric + 1 = t.summon_state_version::numeric
                                    ELSE FALSE END
                           ) AS matching_count
                    FROM terminal t
                    LEFT JOIN processed_operations op
                      ON op.operation_type = t.expected_type
                     AND op.result #>> '{contract,contract_id}' = t.contract_id::text
                    GROUP BY t.contract_id, t.player_id, t.family_id, t.tier, t.content_version, t.contract_status,
                             t.contract_state_version, t.reward_operation_id, t.summon_id, t.summon_status,
                             t.owner_backend_id, t.summon_state_version, t.expected_type
                )
                SELECT contract_id FROM evidence
                WHERE terminal_count <> 1 OR matching_count <> 1
                   OR (contract_status = 'COMPLETED' AND summon_status IS DISTINCT FROM 'DEFEATED')
                   OR (contract_status = 'FAILED' AND summon_status IS DISTINCT FROM 'FAILED')
                   OR (contract_status = 'COMPLETED' AND NOT EXISTS (
                        SELECT 1 FROM processed_operations op
                        WHERE op.operation_id = reward_operation_id
                          AND op.operation_type = 'BOUNTY_BOSS_COMPLETE'
                          AND op.result #>> '{contract,contract_id}' = contract_id::text))
                   OR (contract_status = 'FAILED' AND reward_operation_id IS NOT NULL)
                ORDER BY contract_id LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID contractId = rows.getObject("contract_id", UUID.class);
                    issues.add(new IntegrityIssue(IntegritySeverity.CRITICAL,
                            "BOUNTY_TERMINAL_EVIDENCE_MISMATCH", contractId.toString(),
                            "Terminal Bounty contract/summon state does not match exactly one completion/failure operation and frozen content version"));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
