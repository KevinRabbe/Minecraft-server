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

/** Read-only bounded reconciliation of immutable craft, output, commission, and Crafting-XP evidence. */
public final class CraftingIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String PERSONAL_OPERATION = "CRAFT_EXECUTE";
    private static final String COMMISSION_OPERATION = "CRAFTING_COMMISSION_COMPLETE";
    private static final String XP_OPERATION = "SKILL_XP_AWARD";
    private static final String XP_REASON = "craft.experience";
    private static final String COMMISSION_SOURCE = "CRAFTING_COMMISSION";

    private final DataSource dataSource;

    public CraftingIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyCraftRecordEvidence(connection, issues, maxIssues);
            verifyCraftOutputEvidence(connection, issues, maxIssues);
            verifyCraftExperienceEvidence(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyCraftRecordEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH craft AS (
                    SELECT c.craft_id,
                           c.operation_id,
                           c.player_id,
                           c.recipe_id,
                           c.recipe_version,
                           c.result_data,
                           c.result_data -> 'result' AS frozen_result,
                           c.result_data ->> 'source_kind' AS source_kind,
                           c.result_data ->> 'source_id' AS source_id
                    FROM craft_records c
                )
                SELECT craft.craft_id, craft.operation_id, craft.source_kind
                FROM craft
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = craft.operation_id
                LEFT JOIN crafting_commissions commission
                  ON commission.completion_craft_id = craft.craft_id
                WHERE jsonb_typeof(craft.result_data) IS DISTINCT FROM 'object'
                   OR jsonb_typeof(craft.frozen_result) IS DISTINCT FROM 'object'
                   OR craft.frozen_result ->> 'craft_id' IS DISTINCT FROM craft.craft_id::TEXT
                   OR craft.frozen_result ->> 'operation_id' IS DISTINCT FROM craft.operation_id::TEXT
                   OR craft.frozen_result ->> 'crafter_player_id' IS DISTINCT FROM craft.player_id::TEXT
                   OR craft.frozen_result ->> 'recipe_id' IS DISTINCT FROM craft.recipe_id
                   OR craft.frozen_result ->> 'recipe_version' IS DISTINCT FROM craft.recipe_version::TEXT
                   OR (
                       craft.source_kind IS NULL
                       AND (
                            craft.source_id IS NOT NULL
                         OR operation.operation_id IS NULL
                         OR operation.operation_type IS DISTINCT FROM ?
                         OR operation.result IS DISTINCT FROM craft.result_data
                         OR craft.frozen_result ->> 'recipient_player_id' IS DISTINCT FROM craft.player_id::TEXT
                       )
                   )
                   OR (
                       craft.source_kind = ?
                       AND (
                            craft.source_id IS NULL
                         OR operation.operation_id IS NULL
                         OR operation.operation_type IS DISTINCT FROM ?
                         OR operation.result ->> 'commission_id' IS DISTINCT FROM craft.source_id
                         OR operation.result ->> 'worker_player_id' IS DISTINCT FROM craft.player_id::TEXT
                         OR operation.result -> 'craft' IS DISTINCT FROM craft.frozen_result
                         OR commission.commission_id IS NULL
                         OR commission.commission_id::TEXT IS DISTINCT FROM craft.source_id
                         OR commission.status IS DISTINCT FROM 'COMPLETED'
                         OR commission.settle_operation_id IS DISTINCT FROM craft.operation_id
                         OR commission.worker_player_id IS DISTINCT FROM craft.player_id
                         OR commission.requester_player_id::TEXT
                              IS DISTINCT FROM craft.frozen_result ->> 'recipient_player_id'
                         OR commission.recipe_id IS DISTINCT FROM craft.recipe_id
                         OR commission.recipe_version IS DISTINCT FROM craft.recipe_version
                       )
                   )
                   OR (craft.source_kind IS NOT NULL AND craft.source_kind IS DISTINCT FROM ?)
                ORDER BY craft.craft_id
                LIMIT ?
                """)) {
            statement.setString(1, PERSONAL_OPERATION);
            statement.setString(2, COMMISSION_SOURCE);
            statement.setString(3, COMMISSION_OPERATION);
            statement.setString(4, COMMISSION_SOURCE);
            statement.setInt(5, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID craftId = rows.getObject("craft_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CRAFT_RECORD_EVIDENCE_MISMATCH",
                            craftId.toString(),
                            "Immutable craft record does not reconcile with its personal/commission source operation "
                                    + rows.getObject("operation_id", UUID.class)
                    ));
                }
            }
        }
    }

    private static void verifyCraftOutputEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH craft AS (
                    SELECT c.craft_id,
                           c.operation_id,
                           c.player_id,
                           c.result_data -> 'result' AS frozen_result
                    FROM craft_records c
                )
                SELECT craft.craft_id,
                       craft.operation_id,
                       craft.frozen_result ->> 'delivery_id' AS delivery_id,
                       craft.frozen_result ->> 'item_instance_id' AS item_instance_id
                FROM craft
                LEFT JOIN pending_commodity_deliveries commodity_delivery
                  ON commodity_delivery.delivery_id::TEXT = craft.frozen_result ->> 'delivery_id'
                LEFT JOIN pending_unique_deliveries unique_delivery
                  ON unique_delivery.delivery_id::TEXT = craft.frozen_result ->> 'delivery_id'
                LEFT JOIN item_instances item
                  ON item.item_instance_id::TEXT = craft.frozen_result ->> 'item_instance_id'
                LEFT JOIN item_provenance creation
                  ON creation.item_instance_id = item.item_instance_id
                 AND creation.sequence_no = 0
                WHERE jsonb_typeof(craft.frozen_result) IS DISTINCT FROM 'object'
                   OR craft.frozen_result ->> 'delivery_id' IS NULL
                   OR (
                       craft.frozen_result ->> 'item_instance_id' IS NULL
                       AND (
                            commodity_delivery.delivery_id IS NULL
                         OR unique_delivery.delivery_id IS NOT NULL
                         OR commodity_delivery.source_operation_id IS DISTINCT FROM craft.operation_id
                         OR commodity_delivery.player_id::TEXT
                              IS DISTINCT FROM craft.frozen_result ->> 'recipient_player_id'
                         OR commodity_delivery.commodity_definition_id
                              IS DISTINCT FROM craft.frozen_result ->> 'output_definition_id'
                         OR commodity_delivery.quantity::TEXT
                              IS DISTINCT FROM craft.frozen_result ->> 'output_quantity'
                         OR (
                             SELECT COUNT(*)
                             FROM economic_ledger ledger
                             WHERE ledger.operation_id = craft.operation_id
                               AND ledger.player_id::TEXT = craft.frozen_result ->> 'recipient_player_id'
                               AND ledger.asset_type = 'COMMODITY'
                               AND ledger.asset_id = craft.frozen_result ->> 'output_definition_id'
                               AND ledger.amount::TEXT = craft.frozen_result ->> 'output_quantity'
                               AND ledger.direction = 'CREDIT'
                         ) <> 1
                       )
                   )
                   OR (
                       craft.frozen_result ->> 'item_instance_id' IS NOT NULL
                       AND (
                            craft.frozen_result ->> 'output_quantity' IS DISTINCT FROM '1'
                         OR unique_delivery.delivery_id IS NULL
                         OR commodity_delivery.delivery_id IS NOT NULL
                         OR unique_delivery.issue_operation_id IS DISTINCT FROM craft.operation_id
                         OR unique_delivery.recipient_player_id::TEXT
                              IS DISTINCT FROM craft.frozen_result ->> 'recipient_player_id'
                         OR unique_delivery.item_instance_id::TEXT
                              IS DISTINCT FROM craft.frozen_result ->> 'item_instance_id'
                         OR item.item_instance_id IS NULL
                         OR item.definition_id IS DISTINCT FROM craft.frozen_result ->> 'output_definition_id'
                         OR item.original_owner_player_id::TEXT
                              IS DISTINCT FROM craft.frozen_result ->> 'recipient_player_id'
                         OR item.created_by_operation_id IS DISTINCT FROM craft.operation_id
                         OR item.roll_state IS DISTINCT FROM COALESCE(
                              craft.frozen_result -> 'roll_quality_basis_points', '{}'::JSONB
                         )
                         OR creation.item_instance_id IS NULL
                         OR creation.operation_id IS DISTINCT FROM craft.operation_id
                         OR creation.event_type IS DISTINCT FROM 'CREATED'
                         OR creation.from_location_kind IS NOT NULL
                         OR creation.from_location_id IS NOT NULL
                         OR creation.to_location_kind IS DISTINCT FROM 'PENDING_DELIVERY'
                         OR creation.to_location_id::TEXT IS DISTINCT FROM craft.frozen_result ->> 'delivery_id'
                         OR creation.actor_player_id IS DISTINCT FROM craft.player_id
                         OR (
                             SELECT COUNT(*)
                             FROM economic_ledger ledger
                             WHERE ledger.operation_id = craft.operation_id
                               AND ledger.player_id::TEXT = craft.frozen_result ->> 'recipient_player_id'
                               AND ledger.asset_type = 'ITEM_INSTANCE'
                               AND ledger.asset_id = craft.frozen_result ->> 'item_instance_id'
                               AND ledger.amount = 1
                               AND ledger.direction = 'CREDIT'
                         ) <> 1
                       )
                   )
                ORDER BY craft.craft_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID craftId = rows.getObject("craft_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CRAFT_OUTPUT_EVIDENCE_MISMATCH",
                            craftId.toString(),
                            "Craft output delivery/issuance evidence does not reconcile with frozen craft result"
                    ));
                }
            }
        }
    }

    private static void verifyCraftExperienceEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT fulfillment.craft_id,
                       fulfillment.xp_operation_id
                FROM craft_experience_fulfillments fulfillment
                JOIN craft_records craft ON craft.craft_id = fulfillment.craft_id
                LEFT JOIN skill_xp_awards award ON award.operation_id = fulfillment.xp_operation_id
                LEFT JOIN processed_operations operation ON operation.operation_id = fulfillment.xp_operation_id
                WHERE award.operation_id IS NULL
                   OR award.player_id IS DISTINCT FROM craft.player_id
                   OR award.reason IS DISTINCT FROM ?
                   OR operation.operation_id IS NULL
                   OR operation.operation_type IS DISTINCT FROM ?
                   OR operation.result ->> 'player_id' IS DISTINCT FROM award.player_id::TEXT
                   OR operation.result ->> 'skill_id' IS DISTINCT FROM award.skill_id
                   OR operation.result ->> 'requested_experience'
                        IS DISTINCT FROM award.requested_experience::TEXT
                   OR operation.result ->> 'granted_experience'
                        IS DISTINCT FROM award.granted_experience::TEXT
                   OR operation.result ->> 'previous_experience'
                        IS DISTINCT FROM award.previous_experience::TEXT
                   OR operation.result ->> 'new_experience'
                        IS DISTINCT FROM award.new_experience::TEXT
                   OR operation.result ->> 'active_cap' IS DISTINCT FROM award.active_skill_cap::TEXT
                   OR operation.result ->> 'reason' IS DISTINCT FROM award.reason
                ORDER BY fulfillment.craft_id
                LIMIT ?
                """)) {
            statement.setString(1, XP_REASON);
            statement.setString(2, XP_OPERATION);
            statement.setInt(3, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID craftId = rows.getObject("craft_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CRAFT_XP_EVIDENCE_MISMATCH",
                            craftId.toString(),
                            "Craft XP fulfillment does not reconcile with append-only skill XP/processed-operation evidence "
                                    + rows.getObject("xp_operation_id", UUID.class)
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
