package io.github.kevinrabbe.minecraftserver.common.progression;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/** Read-only bounded projection for loading several player skill levels in one database round trip. */
public final class SkillProgressionQueryRepository {
    private static final int MAX_REQUESTED_SKILLS = 64;

    private final DataSource dataSource;
    private final SkillProgressionCatalog catalog;

    public SkillProgressionQueryRepository(DataSource dataSource, SkillProgressionCatalog catalog) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public Map<SkillId, SkillProgressSnapshot> load(
            UUID playerId,
            Collection<SkillId> requestedSkillIds
    ) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(requestedSkillIds, "requestedSkillIds");

        TreeSet<SkillId> skillIds = new TreeSet<>((left, right) -> left.value().compareTo(right.value()));
        for (SkillId skillId : requestedSkillIds) {
            SkillId nonNull = Objects.requireNonNull(skillId, "requestedSkillIds must not contain null");
            catalog.require(nonNull);
            skillIds.add(nonNull);
        }
        if (skillIds.size() > MAX_REQUESTED_SKILLS) {
            throw new IllegalArgumentException(
                    "requestedSkillIds must contain at most " + MAX_REQUESTED_SKILLS + " unique skills"
            );
        }
        if (skillIds.isEmpty()) {
            return Map.of();
        }

        try (Connection connection = dataSource.getConnection()) {
            requirePlayer(connection, playerId);
            int activeCap = readActiveCap(connection);
            Map<SkillId, SkillRow> persisted = loadRows(connection, playerId, skillIds);

            LinkedHashMap<SkillId, SkillProgressSnapshot> result = new LinkedHashMap<>();
            for (SkillId skillId : skillIds) {
                SkillProgressionDefinition definition = catalog.require(skillId);
                SkillRow row = persisted.get(skillId);
                long experience = row == null ? 0L : row.experience();
                long stateVersion = row == null ? 0L : row.stateVersion();
                result.put(skillId, new SkillProgressSnapshot(
                        playerId,
                        skillId,
                        experience,
                        definition.levelForExperience(experience, activeCap),
                        activeCap,
                        stateVersion
                ));
            }
            return Map.copyOf(result);
        }
    }

    public Map<SkillId, Integer> loadLevels(
            UUID playerId,
            Collection<SkillId> requestedSkillIds
    ) throws SQLException {
        LinkedHashMap<SkillId, Integer> levels = new LinkedHashMap<>();
        load(playerId, requestedSkillIds).forEach((skillId, snapshot) -> levels.put(skillId, snapshot.level()));
        return Map.copyOf(levels);
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SkillProgressionException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static int readActiveCap(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT active_skill_cap
                FROM progression_state
                WHERE singleton = TRUE
                """)) {
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SkillProgressionException("Global progression_state row is missing");
                }
                int activeCap = row.getInt("active_skill_cap");
                SkillCapStage.fromActiveCap(activeCap);
                return activeCap;
            }
        }
    }

    private static Map<SkillId, SkillRow> loadRows(
            Connection connection,
            UUID playerId,
            Collection<SkillId> skillIds
    ) throws SQLException {
        Array requested = connection.createArrayOf(
                "text",
                skillIds.stream().map(SkillId::value).toArray(String[]::new)
        );
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT skill_id, experience, state_version
                    FROM player_skills
                    WHERE player_id = ?
                      AND skill_id = ANY (?)
                    """)) {
                statement.setObject(1, playerId);
                statement.setArray(2, requested);
                try (ResultSet rows = statement.executeQuery()) {
                    LinkedHashMap<SkillId, SkillRow> result = new LinkedHashMap<>();
                    while (rows.next()) {
                        SkillId skillId = new SkillId(rows.getString("skill_id"));
                        result.put(skillId, new SkillRow(
                                rows.getLong("experience"),
                                rows.getLong("state_version")
                        ));
                    }
                    return Map.copyOf(result);
                }
            }
        } finally {
            requested.free();
        }
    }

    private record SkillRow(long experience, long stateVersion) {
        private SkillRow {
            if (experience < 0 || stateVersion < 0) {
                throw new IllegalArgumentException("persisted skill row values must be nonnegative");
            }
        }
    }
}
