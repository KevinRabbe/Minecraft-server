package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Startup compatibility gate for persisted Maps that still depend on Paper encounter content.
 *
 * <p>Every non-destroyed Map item remains a player-owned challenge promise and must still be openable under its exact
 * immutable profile. After opening, the source item is DESTROYED and the persistent run becomes the dependency:
 * CREATED and ACTIVE runs may still materialize gameplay, while a COMPLETED run still needs the historical encounter
 * definition until reward settlement freezes durable grants. Settled completed runs and otherwise terminal history no
 * longer consult encounter content and therefore do not keep obsolete definitions alive forever.</p>
 */
final class PaperMapLiveContentCompatibilityValidator {
    private PaperMapLiveContentCompatibilityValidator() { }

    static void validate(
            DataSource dataSource,
            MapAuthorityRepository maps,
            PaperMapEncounterContentCatalog content
    ) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(maps, "maps");
        Objects.requireNonNull(content, "content");

        for (UUID itemInstanceId : dependentMapItemIds(dataSource)) {
            content.require(maps.loadMapProfile(itemInstanceId).runDefinition());
        }
        for (UUID runId : dependentRunIds(dataSource)) {
            content.require(maps.loadRun(runId).definition());
        }
    }

    private static List<UUID> dependentMapItemIds(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT p.item_instance_id
                     FROM map_item_profiles p
                     JOIN item_instances i ON i.item_instance_id = p.item_instance_id
                     WHERE i.location_kind <> 'DESTROYED'
                     ORDER BY p.item_instance_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            ArrayList<UUID> result = new ArrayList<>();
            while (rows.next()) {
                result.add(rows.getObject("item_instance_id", UUID.class));
            }
            return List.copyOf(result);
        }
    }

    private static List<UUID> dependentRunIds(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT run_id
                     FROM map_runs
                     WHERE status IN ('CREATED', 'ACTIVE')
                        OR (status = 'COMPLETED' AND reward_operation_id IS NULL)
                     ORDER BY run_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            ArrayList<UUID> result = new ArrayList<>();
            while (rows.next()) {
                result.add(rows.getObject("run_id", UUID.class));
            }
            return List.copyOf(result);
        }
    }
}
