package io.github.kevinrabbe.minecraftserver.common.pvp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Read-only proxy projection of backend IDs configured as isolated competitive runtimes. */
public final class CompetitiveRuntimeTopologyRepository {
    private final DataSource dataSource;

    public CompetitiveRuntimeTopologyRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Set<String> findBackendIds() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT backend_id
                     FROM competitive_runtime_principals
                     ORDER BY backend_id ASC
                     """);
             ResultSet rows = statement.executeQuery()) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            while (rows.next()) {
                if (!result.add(rows.getString("backend_id"))) {
                    throw new CompetitiveExecutionException("Duplicate competitive runtime backend projection");
                }
            }
            return Set.copyOf(result);
        }
    }
}
