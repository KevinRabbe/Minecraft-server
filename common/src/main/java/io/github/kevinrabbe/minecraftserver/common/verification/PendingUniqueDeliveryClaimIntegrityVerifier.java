package io.github.kevinrabbe.minecraftserver.common.verification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only bounded reconciliation of historical pending-unique-delivery claim consumption evidence. */
public final class PendingUniqueDeliveryClaimIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String CLAIM_OPERATION = "PENDING_UNIQUE_DELIVERY_CLAIM";

    private final DataSource dataSource;

    public PendingUniqueDeliveryClaimIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyClaimEvidence(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyClaimEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH claimed AS (
                    SELECT delivery.delivery_id,
                           delivery.recipient_player_id,
                           delivery.item_instance_id,
                           delivery.claim_operation_id,
                           operation.operation_id AS processed_operation_id,
                           operation.operation_type,
                           operation.result,
                           item.definition_id AS current_definition_id,
                           item.state_version::NUMERIC AS current_item_state_version,
                           provenance.sequence_no::NUMERIC AS provenance_sequence_no,
                           provenance.event_type AS provenance_event_type,
                           provenance.from_location_kind AS provenance_from_location_kind,
                           provenance.from_location_id AS provenance_from_location_id,
                           provenance.to_location_kind AS provenance_to_location_kind,
                           provenance.to_location_id AS provenance_to_location_id,
                           provenance.reason AS provenance_reason,
                           provenance.actor_player_id AS provenance_actor_player_id,
                           state.state_version::NUMERIC AS current_player_state_version,
                           session.player_id AS session_player_id,
                           session.state_version::NUMERIC AS session_state_version
                    FROM pending_unique_deliveries delivery
                    LEFT JOIN processed_operations operation
                      ON operation.operation_id = delivery.claim_operation_id
                    LEFT JOIN item_instances item
                      ON item.item_instance_id = delivery.item_instance_id
                    LEFT JOIN item_provenance provenance
                      ON provenance.item_instance_id = delivery.item_instance_id
                     AND provenance.operation_id = delivery.claim_operation_id
                    LEFT JOIN player_state state
                      ON state.player_id = delivery.recipient_player_id
                    LEFT JOIN player_sessions session
                      ON session.network_session_id::TEXT = operation.result ->> 'session_id'
                    WHERE delivery.status = 'CLAIMED'
                ),
                normalized AS (
                    SELECT claimed.*,
                           result ->> 'delivery_id' AS result_delivery_id,
                           result ->> 'recipient_player_id' AS result_recipient_player_id,
                           result ->> 'item_instance_id' AS result_item_instance_id,
                           result ->> 'definition_id' AS result_definition_id,
                           CASE WHEN result ->> 'item_state_version' ~ '^[1-9][0-9]*$'
                                THEN (result ->> 'item_state_version')::NUMERIC END AS result_item_state_version,
                           CASE WHEN result ->> 'player_state_version' ~ '^[0-9]+$'
                                THEN (result ->> 'player_state_version')::NUMERIC END AS result_player_state_version,
                           result ->> 'session_id' AS result_session_id,
                           result ->> 'backend_id' AS result_backend_id,
                           CASE WHEN result ->> 'expected_player_state_version' ~ '^[0-9]+$'
                                THEN (result ->> 'expected_player_state_version')::NUMERIC END
                                AS expected_player_state_version,
                           result ->> 'payload_sha256' AS payload_sha256,
                           result ->> 'reason' AS result_reason
                    FROM claimed
                ),
                broken_claims AS (
                    SELECT delivery_id::TEXT AS subject_id,
                           'Claimed unique delivery does not reconcile with its processed claim, DELIVERED provenance, and version evidence'
                               AS message
                    FROM normalized
                    WHERE processed_operation_id IS NULL
                       OR operation_type IS DISTINCT FROM ?
                       OR jsonb_typeof(result) IS DISTINCT FROM 'object'
                       OR result_delivery_id IS DISTINCT FROM delivery_id::TEXT
                       OR result_recipient_player_id IS DISTINCT FROM recipient_player_id::TEXT
                       OR result_item_instance_id IS DISTINCT FROM item_instance_id::TEXT
                       OR result_definition_id IS NULL
                       OR result_definition_id IS DISTINCT FROM current_definition_id
                       OR result_item_state_version IS NULL
                       OR current_item_state_version IS NULL
                       OR current_item_state_version < result_item_state_version
                       OR provenance_sequence_no IS DISTINCT FROM result_item_state_version
                       OR provenance_event_type IS DISTINCT FROM 'DELIVERED'
                       OR provenance_from_location_kind IS DISTINCT FROM 'PENDING_DELIVERY'
                       OR provenance_from_location_id IS DISTINCT FROM delivery_id
                       OR provenance_to_location_kind IS DISTINCT FROM 'PLAYER_INVENTORY'
                       OR provenance_to_location_id IS DISTINCT FROM recipient_player_id
                       OR provenance_reason IS DISTINCT FROM result_reason
                       OR provenance_actor_player_id IS DISTINCT FROM recipient_player_id
                       OR result_player_state_version IS NULL
                       OR expected_player_state_version IS NULL
                       OR result_player_state_version <> expected_player_state_version + 1
                       OR current_player_state_version IS NULL
                       OR current_player_state_version < result_player_state_version
                       OR result_session_id IS NULL
                       OR session_player_id IS DISTINCT FROM recipient_player_id
                       OR session_state_version IS NULL
                       OR session_state_version < result_player_state_version
                       OR result_backend_id IS NULL
                       OR BTRIM(result_backend_id) = ''
                       OR (payload_sha256 ~ '^[0-9a-f]{64}$') IS DISTINCT FROM TRUE
                       OR (result_reason ~ '^[a-z0-9][a-z0-9._-]{0,95}$') IS DISTINCT FROM TRUE
                ),
                orphan_claim_operations AS (
                    SELECT operation.operation_id::TEXT AS subject_id,
                           'Processed pending-unique-delivery claim has no matching claimed delivery row' AS message
                    FROM processed_operations operation
                    LEFT JOIN pending_unique_deliveries delivery
                      ON delivery.claim_operation_id = operation.operation_id
                     AND delivery.status = 'CLAIMED'
                    WHERE operation.operation_type = ?
                      AND delivery.delivery_id IS NULL
                )
                SELECT subject_id, message
                FROM (
                    SELECT subject_id, message FROM broken_claims
                    UNION ALL
                    SELECT subject_id, message FROM orphan_claim_operations
                ) broken
                ORDER BY subject_id
                LIMIT ?
                """)) {
            statement.setString(1, CLAIM_OPERATION);
            statement.setString(2, CLAIM_OPERATION);
            statement.setInt(3, maxIssues);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "PENDING_UNIQUE_CLAIM_EVIDENCE_MISMATCH",
                            rows.getString("subject_id"),
                            rows.getString("message")
                    ));
                }
            }
        }
    }
}
