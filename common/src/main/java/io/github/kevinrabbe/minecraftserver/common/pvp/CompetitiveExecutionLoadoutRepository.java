package io.github.kevinrabbe.minecraftserver.common.pvp;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded read-only access to identity-free loadout items frozen onto one competitive execution. */
public final class CompetitiveExecutionLoadoutRepository {
    private static final int MAX_LIMIT = 500;

    private final DataSource dataSource;

    public CompetitiveExecutionLoadoutRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<CompetitiveExecutionLoadoutItem> list(UUID executionId, int limit) throws SQLException {
        Objects.requireNonNull(executionId, "executionId");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT execution_id,
                            participant_index,
                            loadout_item_index,
                            definition_id,
                            roll_state::TEXT AS roll_state_json,
                            upgrade_level
                     FROM competitive_execution_loadout_items
                     WHERE execution_id = ?
                     ORDER BY participant_index ASC, loadout_item_index ASC
                     LIMIT ?
                     """)) {
            statement.setObject(1, executionId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<CompetitiveExecutionLoadoutItem> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new CompetitiveExecutionLoadoutItem(
                            rows.getObject("execution_id", UUID.class),
                            rows.getInt("participant_index"),
                            rows.getInt("loadout_item_index"),
                            rows.getString("definition_id"),
                            rows.getString("roll_state_json"),
                            rows.getInt("upgrade_level")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }
}
