package io.github.kevinrabbe.minecraftserver.common.pvp;

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

/** Read-only access to the sanitized legacy-runtime projection materialized atomically at assignment. */
public final class CompetitiveRuntimeManifestRepository {
    private final DataSource dataSource;

    public CompetitiveRuntimeManifestRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Optional<CompetitiveRuntimeManifest> load(UUID executionId) throws SQLException {
        Objects.requireNonNull(executionId, "executionId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT e.execution_id,
                            e.activity_kind,
                            e.activity_id,
                            e.backend_id,
                            e.status,
                            e.lease_expires_at,
                            e.state_version,
                            s.ruleset_id,
                            s.ruleset_version,
                            s.team_size
                     FROM competitive_executions e
                     JOIN competitive_execution_specs s ON s.execution_id = e.execution_id
                     WHERE e.execution_id = ?
                     """)) {
            statement.setObject(1, executionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                List<CompetitiveRuntimeParticipant> participants = readParticipants(connection, executionId);
                return Optional.of(new CompetitiveRuntimeManifest(
                        row.getObject("execution_id", UUID.class),
                        CompetitiveActivityKind.valueOf(row.getString("activity_kind")),
                        row.getObject("activity_id", UUID.class),
                        row.getString("backend_id"),
                        CompetitiveExecutionStatus.valueOf(row.getString("status")),
                        row.getTimestamp("lease_expires_at").toInstant(),
                        row.getLong("state_version"),
                        row.getString("ruleset_id"),
                        row.getInt("ruleset_version"),
                        row.getInt("team_size"),
                        participants
                ));
            }
        }
    }

    private static List<CompetitiveRuntimeParticipant> readParticipants(Connection connection, UUID executionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT participant_index,
                       side_key,
                       side_id,
                       player_id,
                       minecraft_uuid,
                       player_name
                FROM competitive_execution_participants
                WHERE execution_id = ?
                ORDER BY participant_index ASC
                """)) {
            statement.setObject(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<CompetitiveRuntimeParticipant> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new CompetitiveRuntimeParticipant(
                            rows.getInt("participant_index"),
                            rows.getString("side_key"),
                            rows.getObject("side_id", UUID.class),
                            rows.getObject("player_id", UUID.class),
                            rows.getObject("minecraft_uuid", UUID.class),
                            rows.getString("player_name")
                    ));
                }
                return List.copyOf(result);
            }
        }
    }
}
