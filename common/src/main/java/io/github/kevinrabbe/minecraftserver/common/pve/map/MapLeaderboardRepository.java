package io.github.kevinrabbe.minecraftserver.common.pve.map;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded read-only Persistent-MMO Map rankings derived directly from authoritative clear/run evidence. */
public final class MapLeaderboardRepository {
    private static final int MAX_LIMIT = 100;

    private static final String HIGHEST_SQL = """
            WITH ranked AS (
                SELECT ROW_NUMBER() OVER (
                           ORDER BY mc.difficulty DESC,
                                    mc.elapsed_millis ASC,
                                    mc.completed_at ASC,
                                    mc.clear_id ASC
                       ) AS rank_no,
                       mc.clear_id,
                       mc.run_id,
                       mc.difficulty,
                       mc.elapsed_millis,
                       mc.solo,
                       mc.world_era_id,
                       mc.balance_version,
                       mc.completed_at,
                       mr.environment_id,
                       mr.enemy_family_id,
                       mr.objective_id,
                       mr.modifier_ids::text AS modifier_json
                FROM map_clears mc
                JOIN map_runs mr ON mr.run_id = mc.run_id
                WHERE mc.solo = ?
                ORDER BY mc.difficulty DESC,
                         mc.elapsed_millis ASC,
                         mc.completed_at ASC,
                         mc.clear_id ASC
                LIMIT ?
            )
            SELECT ranked.*,
                   participant.player_id,
                   (
                       SELECT pn.name
                       FROM player_names pn
                       WHERE pn.player_id = participant.player_id
                       ORDER BY pn.last_seen_at DESC, pn.name ASC
                       LIMIT 1
                   ) AS player_name
            FROM ranked
            JOIN map_run_participants participant ON participant.run_id = ranked.run_id
            ORDER BY ranked.rank_no ASC, participant.player_id ASC
            """;

    private static final String FASTEST_SQL = """
            WITH ranked AS (
                SELECT ROW_NUMBER() OVER (
                           ORDER BY mc.elapsed_millis ASC,
                                    mc.completed_at ASC,
                                    mc.clear_id ASC
                       ) AS rank_no,
                       mc.clear_id,
                       mc.run_id,
                       mc.difficulty,
                       mc.elapsed_millis,
                       mc.solo,
                       mc.world_era_id,
                       mc.balance_version,
                       mc.completed_at,
                       mr.environment_id,
                       mr.enemy_family_id,
                       mr.objective_id,
                       mr.modifier_ids::text AS modifier_json
                FROM map_clears mc
                JOIN map_runs mr ON mr.run_id = mc.run_id
                WHERE mc.solo = ?
                  AND mc.difficulty = ?
                ORDER BY mc.elapsed_millis ASC,
                         mc.completed_at ASC,
                         mc.clear_id ASC
                LIMIT ?
            )
            SELECT ranked.*,
                   participant.player_id,
                   (
                       SELECT pn.name
                       FROM player_names pn
                       WHERE pn.player_id = participant.player_id
                       ORDER BY pn.last_seen_at DESC, pn.name ASC
                       LIMIT 1
                   ) AS player_name
            FROM ranked
            JOIN map_run_participants participant ON participant.run_id = ranked.run_id
            ORDER BY ranked.rank_no ASC, participant.player_id ASC
            """;

    private final DataSource dataSource;

    public MapLeaderboardRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Highest difficulty first; equal difficulty prefers faster then earlier clears. */
    public List<MapLeaderboardEntry> highest(boolean solo, int limit) throws SQLException {
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(HIGHEST_SQL)) {
            statement.setBoolean(1, solo);
            statement.setInt(2, limit);
            return readEntries(statement);
        }
    }

    /** Fastest clear at one explicit difficulty and solo/group category. */
    public List<MapLeaderboardEntry> fastest(boolean solo, MapDifficulty difficulty, int limit) throws SQLException {
        Objects.requireNonNull(difficulty, "difficulty");
        requireLimit(limit);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FASTEST_SQL)) {
            statement.setBoolean(1, solo);
            statement.setInt(2, difficulty.value());
            statement.setInt(3, limit);
            return readEntries(statement);
        }
    }

    private static List<MapLeaderboardEntry> readEntries(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            ArrayList<MapLeaderboardEntry> result = new ArrayList<>();
            MutableEntry current = null;
            UUID currentClearId = null;
            while (rows.next()) {
                UUID clearId = rows.getObject("clear_id", UUID.class);
                if (!clearId.equals(currentClearId)) {
                    if (current != null) {
                        result.add(current.freeze());
                    }
                    currentClearId = clearId;
                    current = MutableEntry.from(rows);
                }

                UUID playerId = rows.getObject("player_id", UUID.class);
                String playerName = rows.getString("player_name");
                if (playerName == null || playerName.isBlank()) {
                    throw new MapAuthorityException(
                            "Map leaderboard participant has no current name projection: " + playerId
                    );
                }
                current.participants.add(new MapLeaderboardParticipant(playerId, playerName));
            }
            if (current != null) {
                result.add(current.freeze());
            }
            return List.copyOf(result);
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    private static final class MutableEntry {
        private final int rank;
        private final UUID clearId;
        private final UUID runId;
        private final MapDifficulty difficulty;
        private final long elapsedMillis;
        private final boolean solo;
        private final String environmentId;
        private final String enemyFamilyId;
        private final String objectiveId;
        private final String modifierJson;
        private final String worldEraId;
        private final int balanceVersion;
        private final java.time.Instant completedAt;
        private final ArrayList<MapLeaderboardParticipant> participants = new ArrayList<>();

        private MutableEntry(
                int rank,
                UUID clearId,
                UUID runId,
                MapDifficulty difficulty,
                long elapsedMillis,
                boolean solo,
                String environmentId,
                String enemyFamilyId,
                String objectiveId,
                String modifierJson,
                String worldEraId,
                int balanceVersion,
                java.time.Instant completedAt
        ) {
            this.rank = rank;
            this.clearId = clearId;
            this.runId = runId;
            this.difficulty = difficulty;
            this.elapsedMillis = elapsedMillis;
            this.solo = solo;
            this.environmentId = environmentId;
            this.enemyFamilyId = enemyFamilyId;
            this.objectiveId = objectiveId;
            this.modifierJson = modifierJson;
            this.worldEraId = worldEraId;
            this.balanceVersion = balanceVersion;
            this.completedAt = completedAt;
        }

        private static MutableEntry from(ResultSet row) throws SQLException {
            return new MutableEntry(
                    row.getInt("rank_no"),
                    row.getObject("clear_id", UUID.class),
                    row.getObject("run_id", UUID.class),
                    new MapDifficulty(row.getInt("difficulty")),
                    row.getLong("elapsed_millis"),
                    row.getBoolean("solo"),
                    row.getString("environment_id"),
                    row.getString("enemy_family_id"),
                    row.getString("objective_id"),
                    row.getString("modifier_json"),
                    row.getString("world_era_id"),
                    row.getInt("balance_version"),
                    row.getTimestamp("completed_at").toInstant()
            );
        }

        private MapLeaderboardEntry freeze() {
            return new MapLeaderboardEntry(
                    rank,
                    clearId,
                    runId,
                    difficulty,
                    elapsedMillis,
                    solo,
                    environmentId,
                    enemyFamilyId,
                    objectiveId,
                    modifierJson,
                    worldEraId,
                    balanceVersion,
                    completedAt,
                    participants
            );
        }
    }
}
