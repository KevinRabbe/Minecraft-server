package io.github.kevinrabbe.minecraftserver.common.world;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded read-only projection of canonical world-era and feature-access state. */
public final class WorldProgressionQueryRepository {
    private static final int MAX_LIMIT = 200;

    private final DataSource dataSource;

    public WorldProgressionQueryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Optional<WorldEraSnapshot> currentEra() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT era_id, sequence_no, source_operation_id, started_at
                     FROM world_eras
                     ORDER BY sequence_no DESC
                     LIMIT 1
                     """)) {
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new WorldEraSnapshot(
                        new WorldEraId(row.getString("era_id")),
                        row.getInt("sequence_no"),
                        row.getObject("source_operation_id", UUID.class),
                        row.getTimestamp("started_at").toInstant()
                ));
            }
        }
    }

    /** Returns known persistent feature-access states ordered by stable feature ID. */
    public List<FeatureState> listFeatures(int limit) throws SQLException {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT feature_id, accessibility, source_operation_id, changed_at, state_version
                     FROM feature_states
                     ORDER BY feature_id ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<FeatureState> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new FeatureState(
                            rows.getString("feature_id"),
                            FeatureAccessibility.valueOf(rows.getString("accessibility")),
                            rows.getObject("source_operation_id", UUID.class),
                            rows.getTimestamp("changed_at").toInstant(),
                            rows.getLong("state_version")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
