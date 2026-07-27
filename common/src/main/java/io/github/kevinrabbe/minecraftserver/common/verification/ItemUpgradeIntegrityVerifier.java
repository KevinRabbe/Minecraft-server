package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only bounded reconciliation of current unique-item upgrade state against append-only upgrade evidence. */
public final class ItemUpgradeIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;
    private final Optional<ItemCatalog> itemCatalog;

    /** Compatibility constructor for callers that can only reconcile persisted upgrade evidence. */
    public ItemUpgradeIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Optional.empty();
    }

    /** Strong verifier that also checks current item definitions against the generic equipment-upgrade contract. */
    public ItemUpgradeIntegrityVerifier(DataSource dataSource, ItemCatalog itemCatalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Optional.of(Objects.requireNonNull(itemCatalog, "itemCatalog"));
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            if (itemCatalog.isPresent()) {
                verifyUpgradeDefinitions(connection, itemCatalog.orElseThrow(), issues, maxIssues);
            }
            verifyUpgradeChains(connection, issues, maxIssues);
            verifyUpgradeProvenance(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyUpgradeDefinitions(
            Connection connection,
            ItemCatalog itemCatalog,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT item.item_instance_id,
                       item.definition_id,
                       item.upgrade_level
                FROM item_instances item
                WHERE item.upgrade_level > 0
                   OR EXISTS (
                        SELECT 1
                        FROM item_upgrade_events event
                        WHERE event.item_instance_id = item.item_instance_id
                   )
                ORDER BY item.item_instance_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID itemId = rows.getObject("item_instance_id", UUID.class);
                    String definitionId = rows.getString("definition_id");
                    Optional<ItemDefinition> definition = itemCatalog.find(definitionId);
                    if (definition.isEmpty()) {
                        issues.add(new IntegrityIssue(
                                IntegritySeverity.CRITICAL,
                                "ITEM_UPGRADE_DEFINITION_UNKNOWN",
                                itemId.toString(),
                                "Upgraded live item references unknown definition " + definitionId
                        ));
                        continue;
                    }
                    ItemDefinition known = definition.orElseThrow();
                    if (known.category() != ItemCategory.EQUIPMENT) {
                        issues.add(new IntegrityIssue(
                                IntegritySeverity.CRITICAL,
                                "ITEM_UPGRADE_NON_EQUIPMENT",
                                itemId.toString(),
                                "Generic upgrade state exists on non-equipment definition " + definitionId
                                        + " (category=" + known.category() + ", level="
                                        + rows.getInt("upgrade_level") + ")"
                        ));
                    }
                }
            }
        }
    }

    private static void verifyUpgradeChains(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH upgrade_summary AS (
                    SELECT item.item_instance_id,
                           item.state_version AS current_state_version,
                           item.upgrade_level AS current_upgrade_level,
                           COUNT(event.upgrade_event_id)::INTEGER AS event_count,
                           COUNT(DISTINCT event.to_upgrade_level)::INTEGER AS distinct_to_levels,
                           MIN(event.from_upgrade_level) AS minimum_from_level,
                           MAX(event.to_upgrade_level) AS maximum_to_level,
                           MAX(event.to_state_version) AS maximum_event_state_version
                    FROM item_instances item
                    LEFT JOIN item_upgrade_events event
                      ON event.item_instance_id = item.item_instance_id
                    GROUP BY item.item_instance_id, item.state_version, item.upgrade_level
                )
                SELECT item_instance_id,
                       current_state_version,
                       current_upgrade_level,
                       event_count,
                       distinct_to_levels,
                       minimum_from_level,
                       maximum_to_level,
                       maximum_event_state_version
                FROM upgrade_summary
                WHERE event_count <> current_upgrade_level
                   OR distinct_to_levels <> event_count
                   OR (current_upgrade_level > 0 AND minimum_from_level IS DISTINCT FROM 0)
                   OR (current_upgrade_level > 0 AND maximum_to_level IS DISTINCT FROM current_upgrade_level)
                   OR maximum_event_state_version > current_state_version
                ORDER BY item_instance_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID itemId = rows.getObject("item_instance_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "ITEM_UPGRADE_CHAIN_MISMATCH",
                            itemId.toString(),
                            "Current upgrade level does not reconcile with its continuous upgrade-event chain: level="
                                    + rows.getInt("current_upgrade_level")
                                    + ", events=" + rows.getInt("event_count")
                                    + ", stateVersion=" + rows.getLong("current_state_version")
                    ));
                }
            }
        }
    }

    private static void verifyUpgradeProvenance(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event.item_instance_id,
                       event.operation_id,
                       event.to_state_version
                FROM item_upgrade_events event
                JOIN item_instances item
                  ON item.item_instance_id = event.item_instance_id
                LEFT JOIN item_provenance provenance
                  ON provenance.item_instance_id = event.item_instance_id
                 AND provenance.operation_id = event.operation_id
                 AND provenance.sequence_no = event.to_state_version
                 AND provenance.event_type = 'UPGRADED'
                WHERE provenance.provenance_event_id IS NULL
                   OR provenance.reason IS DISTINCT FROM event.reason
                   OR provenance.actor_player_id IS DISTINCT FROM event.actor_player_id
                   OR provenance.from_location_kind IS DISTINCT FROM provenance.to_location_kind
                   OR provenance.from_location_id IS DISTINCT FROM provenance.to_location_id
                ORDER BY event.item_instance_id, event.to_state_version
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID itemId = rows.getObject("item_instance_id", UUID.class);
                    UUID operationId = rows.getObject("operation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "ITEM_UPGRADE_PROVENANCE_MISMATCH",
                            itemId.toString(),
                            "Upgrade event at state version " + rows.getLong("to_state_version")
                                    + " lacks matching no-custody-change UPGRADED provenance for operation " + operationId
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
