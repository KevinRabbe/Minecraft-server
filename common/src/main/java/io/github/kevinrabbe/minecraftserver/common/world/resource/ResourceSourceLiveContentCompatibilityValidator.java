package io.github.kevinrabbe.minecraftserver.common.world.resource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Startup compatibility gate for resource sources still attached to runnable zone instances. */
public final class ResourceSourceLiveContentCompatibilityValidator {
    private ResourceSourceLiveContentCompatibilityValidator() { }

    /**
     * Verifies that every source on a STARTING/ACTIVE instance still has a loaded stable definition for the same
     * logical zone and template. Quantity, XP and respawn tuning may change behind that stable identity.
     *
     * <p>Stopped/draining historical instances and immutable harvest entitlements do not retain obsolete live content.</p>
     */
    public static void validate(DataSource dataSource, ResourceSourceCatalog catalog) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(catalog, "catalog");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT s.definition_id,
                            z.zone_id,
                            z.template_version
                     FROM resource_sources s
                     JOIN zone_instances z ON z.instance_id = s.instance_id
                     WHERE z.status IN ('STARTING', 'ACTIVE')
                     GROUP BY s.definition_id, z.zone_id, z.template_version
                     ORDER BY s.definition_id, z.zone_id, z.template_version
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String definitionId = rows.getString("definition_id");
                String zoneId = rows.getString("zone_id");
                String templateVersion = rows.getString("template_version");
                ResourceSourceDefinition definition;
                try {
                    definition = catalog.require(definitionId);
                } catch (ResourceSourceException exception) {
                    throw new ResourceSourceException(
                            "Loaded resource content is missing runnable definition " + definitionId
                                    + " for " + zoneId + "/" + templateVersion,
                            exception
                    );
                }
                if (!definition.zoneId().equals(zoneId)
                        || !definition.templateVersion().equals(templateVersion)) {
                    throw new ResourceSourceException(
                            "Loaded resource definition " + definitionId
                                    + " cannot represent runnable source identity " + zoneId + "/" + templateVersion
                                    + "; loaded identity is " + definition.zoneId() + "/" + definition.templateVersion()
                    );
                }
            }
        }
    }
}
