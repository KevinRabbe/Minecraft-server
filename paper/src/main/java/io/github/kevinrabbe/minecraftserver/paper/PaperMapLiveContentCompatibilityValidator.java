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
 * Startup compatibility gate for persisted Map runs that still depend on Paper encounter content.
 *
 * <p>CREATED and ACTIVE runs may still materialize gameplay. A COMPLETED run still needs the exact historical
 * encounter definition until reward settlement freezes its durable grants. Once reward_operation_id exists, later
 * fulfillment/release no longer consults encounter content, so settled or otherwise terminal history does not keep
 * obsolete definitions alive forever.</p>
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

        for (UUID runId : dependentRunIds(dataSource)) {
            content.require(maps.loadRun(runId).definition());
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
