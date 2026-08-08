package io.github.kevinrabbe.minecraftserver.common.pve.map;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

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
import java.util.regex.Pattern;

/**
 * Durable exactly-once bridge from an authoritative managed entity harvest to its issued bootstrap Map.
 *
 * <p>Map creation itself remains owned by {@link MapPendingDeliveryAuthority}. This repository only records the
 * causal classification so a crash between the managed kill and Map issuance/delivery can be recovered without
 * rescanning unbounded history or creating a second reward authority.</p>
 */
public final class StarterMapIssuanceRepository {
    private static final int MAX_LIMIT = 1_000;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;

    public StarterMapIssuanceRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Oldest-first bounded scan of authoritative entity harvests that have no issuance evidence yet. */
    public List<StarterMapIssuanceCandidate> listUnissued(String sourceDefinitionId, int limit) throws SQLException {
        String sourceDefinition = requireId(sourceDefinitionId, "sourceDefinitionId");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT h.operation_id,
                            h.player_id,
                            s.definition_id,
                            (
                                SELECT e.era_id
                                FROM world_eras e
                                WHERE e.started_at <= h.created_at
                                ORDER BY e.sequence_no DESC
                                LIMIT 1
                            ) AS world_era_id,
                            h.created_at
                     FROM resource_harvests h
                     JOIN resource_entity_kill_claims k
                       ON k.operation_id = h.operation_id
                     JOIN resource_sources s
                       ON s.source_id = h.source_id
                     LEFT JOIN starter_map_issuances i
                       ON i.resource_kill_operation_id = h.operation_id
                     WHERE i.resource_kill_operation_id IS NULL
                       AND s.definition_id = ?
                     ORDER BY h.created_at ASC, h.operation_id ASC
                     LIMIT ?
                     """)) {
            statement.setString(1, sourceDefinition);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<StarterMapIssuanceCandidate> result = new ArrayList<>();
                while (rows.next()) {
                    String worldEraId = rows.getString("world_era_id");
                    if (worldEraId == null || worldEraId.isBlank()) {
                        throw new MapAuthorityException(
                                "starter Map managed kill predates every known world era: "
                                        + rows.getObject("operation_id", UUID.class)
                        );
                    }
                    result.add(new StarterMapIssuanceCandidate(
                            rows.getObject("operation_id", UUID.class),
                            rows.getObject("player_id", UUID.class),
                            rows.getString("definition_id"),
                            worldEraId,
                            rows.getTimestamp("created_at").toInstant()
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    /** Records the already-created pending Map delivery as the unique result of one authoritative managed kill. */
    public void recordIssued(
            UUID resourceKillOperationId,
            String sourceDefinitionId,
            UUID issueOperationId,
            UUID playerId,
            MapPendingDeliveryResult pending
    ) throws SQLException {
        Objects.requireNonNull(resourceKillOperationId, "resourceKillOperationId");
        String sourceDefinition = requireId(sourceDefinitionId, "sourceDefinitionId");
        Objects.requireNonNull(issueOperationId, "issueOperationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(pending, "pending");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, issueOperationId);
                Optional<IssuanceRow> existing = findExisting(connection, resourceKillOperationId);
                if (existing.isPresent()) {
                    existing.orElseThrow().requireSame(
                            sourceDefinition,
                            issueOperationId,
                            playerId,
                            pending
                    );
                    connection.commit();
                    return;
                }

                KillEvidence kill = requireAuthoritativeKill(
                        connection,
                        resourceKillOperationId,
                        playerId,
                        sourceDefinition
                );
                if (!kill.worldEraId().equals(pending.mapProfile().runDefinition().worldEraId())) {
                    throw new MapAuthorityException(
                            "starter Map world era does not match authoritative kill-time era"
                    );
                }
                requirePendingMap(
                        connection,
                        pending.deliveryId(),
                        pending.mapProfile().itemInstanceId(),
                        issueOperationId,
                        playerId
                );

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO starter_map_issuances(
                            resource_kill_operation_id,
                            issue_operation_id,
                            player_id,
                            source_definition_id,
                            delivery_id,
                            item_instance_id
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, resourceKillOperationId);
                    statement.setObject(2, issueOperationId);
                    statement.setObject(3, playerId);
                    statement.setString(4, sourceDefinition);
                    statement.setObject(5, pending.deliveryId());
                    statement.setObject(6, pending.mapProfile().itemInstanceId());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static Optional<IssuanceRow> findExisting(Connection connection, UUID resourceKillOperationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT issue_operation_id,
                       player_id,
                       source_definition_id,
                       delivery_id,
                       item_instance_id
                FROM starter_map_issuances
                WHERE resource_kill_operation_id = ?
                """)) {
            statement.setObject(1, resourceKillOperationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new IssuanceRow(
                        row.getObject("issue_operation_id", UUID.class),
                        row.getObject("player_id", UUID.class),
                        row.getString("source_definition_id"),
                        row.getObject("delivery_id", UUID.class),
                        row.getObject("item_instance_id", UUID.class)
                ));
            }
        }
    }

    private static KillEvidence requireAuthoritativeKill(
            Connection connection,
            UUID resourceKillOperationId,
            UUID expectedPlayerId,
            String expectedSourceDefinitionId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT h.player_id,
                       s.definition_id,
                       (
                           SELECT e.era_id
                           FROM world_eras e
                           WHERE e.started_at <= h.created_at
                           ORDER BY e.sequence_no DESC
                           LIMIT 1
                       ) AS world_era_id
                FROM resource_harvests h
                JOIN resource_entity_kill_claims k
                  ON k.operation_id = h.operation_id
                JOIN resource_sources s
                  ON s.source_id = h.source_id
                WHERE h.operation_id = ?
                """)) {
            statement.setObject(1, resourceKillOperationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException(
                            "starter Map issuance requires an authoritative managed entity harvest: "
                                    + resourceKillOperationId
                    );
                }
                UUID playerId = row.getObject("player_id", UUID.class);
                String sourceDefinitionId = row.getString("definition_id");
                String worldEraId = row.getString("world_era_id");
                if (!expectedPlayerId.equals(playerId)
                        || !expectedSourceDefinitionId.equals(sourceDefinitionId)) {
                    throw new MapAuthorityException("starter Map issuance kill identity does not match request");
                }
                if (worldEraId == null || worldEraId.isBlank()) {
                    throw new MapAuthorityException("starter Map kill predates every known world era");
                }
                if (row.next()) {
                    throw new MapAuthorityException("starter Map kill operation resolved to multiple harvests");
                }
                return new KillEvidence(worldEraId);
            }
        }
    }

    private static void requirePendingMap(
            Connection connection,
            UUID deliveryId,
            UUID itemInstanceId,
            UUID expectedIssueOperationId,
            UUID expectedPlayerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT d.issue_operation_id,
                       d.recipient_player_id,
                       d.item_instance_id,
                       p.item_instance_id AS profile_item_instance_id
                FROM pending_unique_deliveries d
                JOIN item_instances i
                  ON i.item_instance_id = d.item_instance_id
                JOIN map_item_profiles p
                  ON p.item_instance_id = i.item_instance_id
                WHERE d.delivery_id = ?
                """)) {
            statement.setObject(1, deliveryId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new MapAuthorityException("starter Map pending delivery does not exist: " + deliveryId);
                }
                if (!expectedIssueOperationId.equals(row.getObject("issue_operation_id", UUID.class))
                        || !expectedPlayerId.equals(row.getObject("recipient_player_id", UUID.class))
                        || !itemInstanceId.equals(row.getObject("item_instance_id", UUID.class))
                        || !itemInstanceId.equals(row.getObject("profile_item_instance_id", UUID.class))) {
                    throw new MapAuthorityException("starter Map pending delivery does not match issuance request");
                }
                if (row.next()) {
                    throw new MapAuthorityException("starter Map delivery resolved to multiple Map profiles");
                }
            }
        }
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (!ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " has invalid format: " + normalized);
        }
        return normalized;
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record KillEvidence(String worldEraId) { }

    private record IssuanceRow(
            UUID issueOperationId,
            UUID playerId,
            String sourceDefinitionId,
            UUID deliveryId,
            UUID itemInstanceId
    ) {
        private void requireSame(
                String requestedSourceDefinitionId,
                UUID requestedIssueOperationId,
                UUID requestedPlayerId,
                MapPendingDeliveryResult pending
        ) {
            if (!issueOperationId.equals(requestedIssueOperationId)
                    || !playerId.equals(requestedPlayerId)
                    || !sourceDefinitionId.equals(requestedSourceDefinitionId)
                    || !deliveryId.equals(pending.deliveryId())
                    || !itemInstanceId.equals(pending.mapProfile().itemInstanceId())) {
                throw new MapAuthorityException(
                        "resource kill already has different starter Map issuance evidence"
                );
            }
        }
    }
}
