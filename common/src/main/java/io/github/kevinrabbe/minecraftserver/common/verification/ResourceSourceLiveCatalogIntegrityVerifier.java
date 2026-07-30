package io.github.kevinrabbe.minecraftserver.common.verification;

import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceCatalog;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceDefinition;
import io.github.kevinrabbe.minecraftserver.common.world.resource.ResourceSourceException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Catalog-aware read-only verification for resource definitions still needed by runnable zone instances.
 *
 * <p>Only STARTING/ACTIVE instances pin a source definition. Historical sources on draining/stopped instances and
 * immutable harvest entitlements already contain their exact issued value and therefore do not retain old live content.
 * Balance values may change behind a stable definition ID; zone/template identity may not drift underneath a runnable
 * source.</p>
 */
public final class ResourceSourceLiveCatalogIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;
    private final ResourceSourceCatalog catalog;

    public ResourceSourceLiveCatalogIntegrityVerifier(DataSource dataSource, ResourceSourceCatalog catalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT s.definition_id,
                           z.zone_id,
                           z.template_version
                    FROM resource_sources s
                    JOIN zone_instances z ON z.instance_id = s.instance_id
                    WHERE z.status IN ('STARTING', 'ACTIVE')
                    GROUP BY s.definition_id, z.zone_id, z.template_version
                    ORDER BY s.definition_id, z.zone_id, z.template_version
                    """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next() && issues.size() < maxIssues) {
                        String definitionId = rows.getString("definition_id");
                        String zoneId = rows.getString("zone_id");
                        String templateVersion = rows.getString("template_version");
                        ResourceSourceDefinition definition;
                        try {
                            definition = catalog.require(definitionId);
                        } catch (ResourceSourceException exception) {
                            issues.add(issue(
                                    definitionId,
                                    zoneId,
                                    templateVersion,
                                    "Runnable resource source references a definition absent from the loaded catalog"
                            ));
                            continue;
                        }
                        if (!definition.zoneId().equals(zoneId)
                                || !definition.templateVersion().equals(templateVersion)) {
                            issues.add(issue(
                                    definitionId,
                                    zoneId,
                                    templateVersion,
                                    "Runnable resource source definition no longer matches its stable zone/template identity"
                            ));
                        }
                    }
                }
            }
            return List.copyOf(issues);
        }
    }

    private static IntegrityIssue issue(
            String definitionId,
            String zoneId,
            String templateVersion,
            String message
    ) {
        return new IntegrityIssue(
                IntegritySeverity.CRITICAL,
                "RESOURCE_SOURCE_LIVE_CONTENT_MISMATCH",
                definitionId + "@" + zoneId + "/" + templateVersion,
                message
        );
    }
}
