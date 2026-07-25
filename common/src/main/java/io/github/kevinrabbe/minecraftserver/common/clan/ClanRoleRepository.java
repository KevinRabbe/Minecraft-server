package io.github.kevinrabbe.minecraftserver.common.clan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Explicit MEMBER/OFFICER role management; leadership is owned only by leadership transfer. */
public final class ClanRoleRepository {
    private static final String SET_ROLE_OPERATION = "CLAN_MEMBER_ROLE_SET";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public ClanRoleRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public ClanMemberSnapshot setMemberRole(
            UUID operationId,
            UUID clanId,
            UUID leaderPlayerId,
            UUID targetPlayerId,
            ClanRole newRole
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(leaderPlayerId, "leaderPlayerId");
        Objects.requireNonNull(targetPlayerId, "targetPlayerId");
        Objects.requireNonNull(newRole, "newRole");
        if (leaderPlayerId.equals(targetPlayerId)) {
            throw new IllegalArgumentException("leader cannot change their own role with setMemberRole");
        }
        if (newRole == ClanRole.LEADER) {
            throw new IllegalArgumentException("use transferLeadership to assign LEADER");
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), operationId);
                    requireUuid(data, "clan_id", clanId, operationId);
                    requireUuid(data, "leader_player_id", leaderPlayerId, operationId);
                    requireUuid(data, "target_player_id", targetPlayerId, operationId);
                    requireString(data, "new_role", newRole.name(), operationId);
                    ClanMemberSnapshot result = memberFrom(data.get("member"));
                    connection.commit();
                    return result;
                }

                LockedClan clan = lockClan(connection, clanId);
                Map<UUID, ClanMemberSnapshot> members = lockMembers(
                        connection, clanId, leaderPlayerId, targetPlayerId
                );
                ClanMemberSnapshot leader = members.get(leaderPlayerId);
                ClanMemberSnapshot target = members.get(targetPlayerId);
                if (leader.role() != ClanRole.LEADER) {
                    throw new ClanMembershipException("only the clan leader may change officer roles");
                }
                if (target.role() == ClanRole.LEADER) {
                    throw new ClanMembershipException("LEADER role is managed only by leadership transfer");
                }

                if (target.role() != newRole) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE clan_members
                            SET role = ?
                            WHERE clan_id = ? AND player_id = ? AND role <> 'LEADER'
                            """)) {
                        statement.setString(1, newRole.name());
                        statement.setObject(2, clanId);
                        statement.setObject(3, targetPlayerId);
                        if (statement.executeUpdate() != 1) {
                            throw new ClanMembershipException("clan role changed concurrently");
                        }
                    }
                    touchClan(connection, clan);
                }

                ClanMemberSnapshot result = readMember(connection, clanId, targetPlayerId);
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("clan_id", clanId.toString());
                data.put("leader_player_id", leaderPlayerId.toString());
                data.put("target_player_id", targetPlayerId.toString());
                data.put("new_role", newRole.name());
                data.put("member", memberMap(result));
                insertProcessed(connection, operationId, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static LockedClan lockClan(Connection connection, UUID clanId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state_version
                FROM clans
                WHERE clan_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanMembershipException("Unknown clan_id: " + clanId);
                }
                return new LockedClan(clanId, row.getLong("state_version"));
            }
        }
    }

    private static Map<UUID, ClanMemberSnapshot> lockMembers(
            Connection connection,
            UUID clanId,
            UUID first,
            UUID second
    ) throws SQLException {
        List<UUID> ids = new java.util.ArrayList<>(List.of(first, second));
        ids.sort(Comparator.comparing(UUID::toString));
        LinkedHashMap<UUID, ClanMemberSnapshot> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id, role, joined_at
                FROM clan_members
                WHERE clan_id = ? AND player_id IN (?, ?)
                ORDER BY player_id
                FOR UPDATE
                """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, ids.get(0));
            statement.setObject(3, ids.get(1));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    result.put(playerId, new ClanMemberSnapshot(
                            clanId,
                            playerId,
                            ClanRole.valueOf(rows.getString("role")),
                            rows.getTimestamp("joined_at").toInstant()
                    ));
                }
            }
        }
        if (!result.containsKey(first) || !result.containsKey(second)) {
            throw new ClanMembershipException("both players must be clan members");
        }
        return Map.copyOf(result);
    }

    private static ClanMemberSnapshot readMember(Connection connection, UUID clanId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role, joined_at
                FROM clan_members
                WHERE clan_id = ? AND player_id = ?
                """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanMembershipException("player is not a clan member: " + playerId);
                }
                return new ClanMemberSnapshot(
                        clanId,
                        playerId,
                        ClanRole.valueOf(row.getString("role")),
                        row.getTimestamp("joined_at").toInstant()
                );
            }
        }
    }

    private static void touchClan(Connection connection, LockedClan clan) throws SQLException {
        long nextVersion;
        try {
            nextVersion = Math.addExact(clan.stateVersion(), 1L);
        } catch (ArithmeticException exception) {
            throw new ClanMembershipException("clan state_version overflow: " + clan.clanId(), exception);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE clans
                SET state_version = ?, updated_at = NOW()
                WHERE clan_id = ? AND state_version = ?
                """)) {
            statement.setLong(1, nextVersion);
            statement.setObject(2, clan.clanId());
            statement.setLong(3, clan.stateVersion());
            if (statement.executeUpdate() != 1) {
                throw new ClanMembershipException("clan changed concurrently: " + clan.clanId());
            }
        }
    }

    private static Optional<ProcessedOperation> findProcessed(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type, result::text AS result_json
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new ProcessedOperation(
                        row.getString("operation_type"),
                        readJsonMap(row.getString("result_json"))
                ));
            }
        }
    }

    private static void insertProcessed(Connection connection, UUID operationId, Map<String, Object> data)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, SET_ROLE_OPERATION);
            statement.setString(3, writeJson(data));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requireType(ProcessedOperation operation, UUID operationId) {
        if (!SET_ROLE_OPERATION.equals(operation.operationType())) {
            throw new ClanMembershipException(
                    "operation_id " + operationId + " already belongs to " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static Map<String, Object> memberMap(ClanMemberSnapshot member) {
        return Map.of(
                "clan_id", member.clanId().toString(),
                "player_id", member.playerId().toString(),
                "role", member.role().name(),
                "joined_at", member.joinedAt().toString()
        );
    }

    private static ClanMemberSnapshot memberFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "member");
        return new ClanMemberSnapshot(
                uuidValue(value, "clan_id"),
                uuidValue(value, "player_id"),
                ClanRole.valueOf(stringValue(value, "role")),
                Instant.parse(stringValue(value, "joined_at"))
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ClanMembershipException("Could not parse clan role idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ClanMembershipException("Could not serialize clan role idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ClanMembershipException("clan role field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) throw new ClanMembershipException("missing clan role field: " + field);
        return Objects.toString(raw);
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!uuidValue(data, field).equals(expected)) throw reused(operationId);
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) throw reused(operationId);
    }

    private static ClanMembershipException reused(UUID operationId) {
        return new ClanMembershipException("operation_id reused with a different clan role request: " + operationId);
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedClan(UUID clanId, long stateVersion) { }

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(result, "result")));
        }
    }
}
