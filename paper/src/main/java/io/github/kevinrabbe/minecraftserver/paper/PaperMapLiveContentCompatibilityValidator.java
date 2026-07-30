package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityException;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapAuthorityRepository;
import io.github.kevinrabbe.minecraftserver.common.pve.map.MapItemProfile;

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
 * Startup compatibility gate for persisted Maps that still depend on Paper encounter content or routing.
 *
 * <p>Every non-destroyed Map item remains a player-owned challenge promise and must still be openable under its exact
 * immutable profile and environment route. After opening, the source item is DESTROYED and the persistent run becomes
 * the content dependency: CREATED and ACTIVE runs may still materialize gameplay, while a COMPLETED run still needs the
 * historical encounter definition until reward settlement freezes durable grants. BOUND CREATED/ACTIVE Paper runs also
 * retain their exact reserved zone/template target, which must remain recognized by the current route catalog so the
 * destination backend still installs Map gameplay. Settled completed runs and otherwise terminal history no longer keep
 * obsolete encounter definitions or routes alive forever.</p>
 */
final class PaperMapLiveContentCompatibilityValidator {
    private PaperMapLiveContentCompatibilityValidator() { }

    static void validate(
            DataSource dataSource,
            MapAuthorityRepository maps,
            PaperMapEncounterContentCatalog content,
            PaperMapEncounterRouteCatalog routes
    ) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(maps, "maps");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(routes, "routes");

        for (UUID itemInstanceId : dependentMapItemIds(dataSource)) {
            MapItemProfile profile = maps.loadMapProfile(itemInstanceId);
            content.require(profile.runDefinition());
            routes.require(profile.runDefinition().environmentId());
        }
        for (UUID runId : dependentRunIds(dataSource)) {
            content.require(maps.loadRun(runId).definition());
        }
        validateBoundGameplayTargets(dataSource, routes);
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

    private static void validateBoundGameplayTargets(
            DataSource dataSource,
            PaperMapEncounterRouteCatalog routes
    ) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT r.run_id, r.target_zone_id, r.target_template_version
                     FROM map_encounter_reservations r
                     JOIN map_runs m ON m.run_id = r.run_id
                     WHERE r.status = 'BOUND'
                       AND m.status IN ('CREATED', 'ACTIVE')
                     ORDER BY r.run_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String zoneId = rows.getString("target_zone_id");
                String templateVersion = rows.getString("target_template_version");
                if (!routes.containsTarget(zoneId, templateVersion)) {
                    throw new MapAuthorityException(
                            "Live Map run target is no longer configured: "
                                    + rows.getObject("run_id", UUID.class) + " -> " + zoneId + "/" + templateVersion
                    );
                }
            }
        }
    }
}
