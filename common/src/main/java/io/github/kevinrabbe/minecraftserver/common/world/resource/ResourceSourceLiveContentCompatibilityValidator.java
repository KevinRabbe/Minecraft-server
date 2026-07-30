package io.github.kevinrabbe.minecraftserver.common.world.resource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Startup compatibility gate for live/recoverable renewable source state. */
public final class ResourceSourceLiveContentCompatibilityValidator {
    private ResourceSourceLiveContentCompatibilityValidator() { }

    public static void validate(DataSource dataSource, ResourceSourceCatalog catalog) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(catalog, "catalog");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT s.source_id,
                            s.definition_id,
                            z.zone_id,
                            z.template_version,
                            z.status
                     FROM resource_sources s
                     JOIN zone_instances z ON z.instance_id = s.instance_id
                     WHERE z.status IN ('STARTING', 'ACTIVE', 'DRAINING')
                        OR EXISTS (
                            SELECT 1
                            FROM resource_entity_spawns e
                            WHERE e.source_id = s.source_id
                              AND e.status IN ('PENDING', 'ACTIVE')
                        )
                     ORDER BY s.source_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID sourceId = rows.getObject("source_id", UUID.class);
                ResourceSourceDefinition definition = catalog.require(rows.getString("definition_id"));
                String zoneId = rows.getString("zone_id");
                String templateVersion = rows.getString("template_version");
                if (!definition.zoneId().equals(zoneId)
                        || !definition.templateVersion().equals(templateVersion)) {
                    throw new ResourceSourceException(
                            "Loaded resource definition does not match live source " + sourceId
                                    + ": persisted=" + zoneId + "/" + templateVersion
                                    + ", loaded=" + definition.zoneId() + "/" + definition.templateVersion()
                    );
                }
            }
        }
    }
}
