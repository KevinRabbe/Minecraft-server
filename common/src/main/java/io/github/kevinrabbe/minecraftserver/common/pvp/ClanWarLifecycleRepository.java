package io.github.kevinrabbe.minecraftserver.common.pvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Challenge/accept/roster/start authority for the isolated 1.8.9 Clan-War category. */
public final class ClanWarLifecycleRepository {
    private static final String CHALLENGE_OPERATION = "CLAN_WAR_CHALLENGE";
    private static final String ACCEPT_OPERATION = "CLAN_WAR_ACCEPT";
    private static final String ROSTER_OPERATION = "CLAN_WAR_SET_ROSTER";
    private static final String LOCK_OPERATION = "CLAN_WAR_LOCK_ROSTER";
    private static final String START_OPERATION = "CLAN_WAR_START";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final ClanWarRuleset ruleset;

    public ClanWarLifecycleRepository(DataSource dataSource, ClanWarRuleset ruleset) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.ruleset = Objects.requireNonNull(ruleset, "ruleset");
    }

    public Optional<ClanWarSnapshot> loadWar(UUID warId) throws SQLException {
        Objects.requireNonNull(warId, "warId");
        try (Connection connection = dataSource.getConnection()) {
            return readWar(connection, warId, false);
        }
    }

    public List<ClanWarRosterEntry> loadRoster(UUID warId) throws SQLException {
        Objects.requireNonNull(warId, "warId");
        try (Connection connection = dataSource.getConnection()) {
            requireWar(connection, warId, false);
            return readRoster(connection, warId);
        }
    }

    public ClanWarSnapshot challenge(
            UUID operationId,
            UUID actingPlayerId,
            UUID challengerClanId,
            UUID defenderClanId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(actingPlayerId, "actingPlayerId");
        Objects.requireNonNull(challengerClanId, "challengerClanId");
        Objects.requireNonNull(defenderClanId, "defenderClanId");
        if (challengerClanId.equals(defenderClanId)) {
            throw new ClanWarException("a clan cannot challenge itself");
        }
        Map<String, Object> request = requestMap(
                "acting_player_id", actingPlayerId,
                "challenger_clan_id", challengerClanId,
                "defender_clan_id", defenderClanId,
                "ruleset_id", ruleset.rulesetId(),
                "ruleset_version", ruleset.rulesetVersion(),
                "rating_policy_version", ruleset.ratingPolicyVersion(),
                "rating_k_factor", ruleset.kFactor(),
                "team_size", ruleset.teamSize()
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ClanWarSnapshot replay = warFrom(requireReplay(
                            processed.orElseThrow(), CHALLENGE_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                requireClan(connection, challengerClanId);
                requireClan(connection, defenderClanId);
                requireOfficerOrLeader(connection, challengerClanId, actingPlayerId);
                ensureRating(connection, challengerClanId);
                ensureRating(connection, defenderClanId);

                UUID warId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO clan_wars(
                            war_id,
                            challenger_clan_id,
                            defender_clan_id,
                            status,
                            state_version,
                            ruleset_id,
                            ruleset_version,
                            rating_policy_version,
                            rating_k_factor,
                            team_size,
                            challenged_by_player_id
                        ) VALUES (?, ?, ?, 'CHALLENGED', 0, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, warId);
                    statement.setObject(2, challengerClanId);
                    statement.setObject(3, defenderClanId);
                    statement.setString(4, ruleset.rulesetId());
                    statement.setInt(5, ruleset.rulesetVersion());
                    statement.setInt(6, ruleset.ratingPolicyVersion());
                    statement.setInt(7, ruleset.kFactor());
                    statement.setInt(8, ruleset.teamSize());
                    statement.setObject(9, actingPlayerId);
                    statement.executeUpdate();
                }

                ClanWarSnapshot result = requireWar(connection, warId, false);
                insertProcessed(connection, operationId, CHALLENGE_OPERATION, request, warMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanWarSnapshot accept(UUID operationId, UUID warId, UUID actingPlayerId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(warId, "warId");
        Objects.requireNonNull(actingPlayerId, "actingPlayerId");
        Map<String, Object> request = requestMap("war_id", warId, "acting_player_id", actingPlayerId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ClanWarSnapshot replay = warFrom(requireReplay(
                            processed.orElseThrow(), ACCEPT_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                ClanWarSnapshot current = requireWar(connection, warId, true);
                if (current.status() != ClanWarStatus.CHALLENGED) {
                    throw new ClanWarException("clan war is not acceptable: " + current.status());
                }
                requireOfficerOrLeader(connection, current.defenderClanId(), actingPlayerId);
                ClanWarSnapshot result = transition(
                        connection, current, ClanWarStatus.ACCEPTED, actingPlayerId, null
                );
                insertProcessed(connection, operationId, ACCEPT_OPERATION, request, warMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public List<ClanWarRosterEntry> setRoster(
            UUID operationId,
            UUID warId,
            UUID actingPlayerId,
            UUID clanId,
            List<UUID> playerIds
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(warId, "warId");
        Objects.requireNonNull(actingPlayerId, "actingPlayerId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(playerIds, "playerIds");
        List<UUID> roster = normalizedRoster(playerIds);
        Map<String, Object> request = requestMap(
                "war_id", warId,
                "acting_player_id", actingPlayerId,
                "clan_id", clanId,
                "player_ids", roster.stream().map(UUID::toString).toList()
        );

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    List<ClanWarRosterEntry> replay = rosterFrom(requireReplay(
                            processed.orElseThrow(), ROSTER_OPERATION, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }

                ClanWarSnapshot war = requireWar(connection, warId, true);
                if (war.status() != ClanWarStatus.ACCEPTED) {
                    throw new ClanWarException("clan war roster is not editable: " + war.status());
                }
                if (!clanId.equals(war.challengerClanId()) && !clanId.equals(war.defenderClanId())) {
                    throw new ClanWarException("roster clan is not a participant in war");
                }
                requireOfficerOrLeader(connection, clanId, actingPlayerId);
                if (roster.size() != war.teamSize()) {
                    throw new ClanWarException("roster must contain exactly " + war.teamSize() + " players");
                }
                for (UUID playerId : roster) {
                    requireClanMember(connection, clanId, playerId);
                }

                try (PreparedStatement delete = connection.prepareStatement("""
                        DELETE FROM clan_war_rosters
                        WHERE war_id = ? AND clan_id = ? AND released_at IS NULL
                        """)) {
                    delete.setObject(1, warId);
                    delete.setObject(2, clanId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO clan_war_rosters(war_id, clan_id, player_id)
                        VALUES (?, ?, ?)
                        """)) {
                    for (UUID playerId : roster) {
                        insert.setObject(1, warId);
                        insert.setObject(2, clanId);
                        insert.setObject(3, playerId);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }

                List<ClanWarRosterEntry> result = readRosterForClan(connection, warId, clanId);
                insertProcessed(connection, operationId, ROSTER_OPERATION, request, rosterMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanWarSnapshot lockRoster(UUID operationId, UUID warId) throws SQLException {
        return simpleTransition(operationId, warId, LOCK_OPERATION, ClanWarStatus.ACCEPTED, ClanWarStatus.ROSTER_LOCKED);
    }

    public ClanWarSnapshot start(UUID operationId, UUID warId) throws SQLException {
        return simpleTransition(operationId, warId, START_OPERATION, ClanWarStatus.ROSTER_LOCKED, ClanWarStatus.ACTIVE);
    }

    private ClanWarSnapshot simpleTransition(
            UUID operationId,
            UUID warId,
            String operationType,
            ClanWarStatus required,
            ClanWarStatus target
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(warId, "warId");
        Map<String, Object> request = requestMap("war_id", warId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ClanWarSnapshot replay = warFrom(requireReplay(
                            processed.orElseThrow(), operationType, request, operationId
                    ));
                    connection.commit();
                    return replay;
                }
                ClanWarSnapshot current = requireWar(connection, warId, true);
                if (current.status() != required) {
                    throw new ClanWarException("clan war cannot transition from " + current.status() + " to " + target);
                }
                ClanWarSnapshot result = transition(connection, current, target, null, null);
                insertProcessed(connection, operationId, operationType, request, warMap(result));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    static Optional<ClanWarSnapshot> readWar(Connection connection, UUID warId, boolean forUpdate) throws SQLException {
        String sql = """
                SELECT war_id,
                       challenger_clan_id,
                       defender_clan_id,
                       status,
                       winning_clan_id,
                       settlement_operation_id,
                       resolution_operation_id,
                       challenged_by_player_id,
                       accepted_by_player_id,
                       ruleset_id,
                       ruleset_version,
                       rating_policy_version,
                       rating_k_factor,
                       team_size,
                       state_version,
                       created_at,
                       started_at,
                       finished_at
                FROM clan_wars
                WHERE war_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, warId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(warSnapshot(row)) : Optional.empty();
            }
        }
    }

    static ClanWarSnapshot requireWar(Connection connection, UUID warId, boolean forUpdate) throws SQLException {
        return readWar(connection, warId, forUpdate)
                .orElseThrow(() -> new ClanWarException("Unknown clan war: " + warId));
    }

    private static ClanWarSnapshot transition(
            Connection connection,
            ClanWarSnapshot current,
            ClanWarStatus target,
            UUID acceptedByPlayerId,
            UUID operationId
    ) throws SQLException {
        long nextVersion = increment(current.stateVersion(), "clan war", current.warId());
        String sql = switch (target) {
            case ACCEPTED -> """
                    UPDATE clan_wars
                    SET status = 'ACCEPTED', accepted_by_player_id = ?, state_version = ?
                    WHERE war_id = ? AND state_version = ? AND status = 'CHALLENGED'
                    RETURNING *
                    """;
            case ROSTER_LOCKED -> """
                    UPDATE clan_wars
                    SET status = 'ROSTER_LOCKED', state_version = ?
                    WHERE war_id = ? AND state_version = ? AND status = 'ACCEPTED'
                    RETURNING *
                    """;
            case ACTIVE -> """
                    UPDATE clan_wars
                    SET status = 'ACTIVE', started_at = NOW(), state_version = ?
                    WHERE war_id = ? AND state_version = ? AND status = 'ROSTER_LOCKED'
                    RETURNING *
                    """;
            default -> throw new IllegalArgumentException("unsupported lifecycle target: " + target);
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (target == ClanWarStatus.ACCEPTED) {
                statement.setObject(parameter++, acceptedByPlayerId);
            }
            statement.setLong(parameter++, nextVersion);
            statement.setObject(parameter++, current.warId());
            statement.setLong(parameter, current.stateVersion());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ClanWarException("clan war changed concurrently: " + current.warId());
                return warSnapshot(row);
            }
        }
    }

    static ClanWarSnapshot warSnapshot(ResultSet row) throws SQLException {
        return new ClanWarSnapshot(
                row.getObject("war_id", UUID.class),
                row.getObject("challenger_clan_id", UUID.class),
                row.getObject("defender_clan_id", UUID.class),
                ClanWarStatus.valueOf(row.getString("status")),
                row.getObject("winning_clan_id", UUID.class),
                row.getObject("settlement_operation_id", UUID.class),
                row.getObject("resolution_operation_id", UUID.class),
                row.getObject("challenged_by_player_id", UUID.class),
                row.getObject("accepted_by_player_id", UUID.class),
                row.getString("ruleset_id"),
                row.getInt("ruleset_version"),
                row.getInt("rating_policy_version"),
                row.getInt("rating_k_factor"),
                row.getInt("team_size"),
                row.getLong("state_version"),
                row.getTimestamp("created_at").toInstant(),
                nullableInstant(row.getTimestamp("started_at")),
                nullableInstant(row.getTimestamp("finished_at"))
        );
    }

    static void requireOfficerOrLeader(Connection connection, UUID clanId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role FROM clan_members WHERE clan_id = ? AND player_id = ? FOR UPDATE
                """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ClanWarException("player is not a member of clan: " + playerId);
                String role = row.getString("role");
                if (!"LEADER".equals(role) && !"OFFICER".equals(role)) {
                    throw new ClanWarException("clan role lacks war-management permission: " + role);
                }
            }
        }
    }

    private static void requireClanMember(Connection connection, UUID clanId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM clan_members WHERE clan_id = ? AND player_id = ?
                """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ClanWarException("player is not a member of roster clan: " + playerId);
            }
        }
    }

    private static void requireClan(Connection connection, UUID clanId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM clans WHERE clan_id = ?")) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new ClanWarException("Unknown clan: " + clanId);
            }
        }
    }

    private void ensureRating(Connection connection, UUID clanId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO clan_war_ratings(clan_id, rating, state_version)
                VALUES (?, ?, 0)
                ON CONFLICT (clan_id) DO NOTHING
                """)) {
            statement.setObject(1, clanId);
            statement.setInt(2, ruleset.initialRating());
            statement.executeUpdate();
        }
    }

    private static List<ClanWarRosterEntry> readRoster(Connection connection, UUID warId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT war_id, clan_id, player_id, locked_at, released_at
                FROM clan_war_rosters
                WHERE war_id = ?
                ORDER BY clan_id, player_id
                """)) {
            statement.setObject(1, warId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ClanWarRosterEntry> result = new ArrayList<>();
                while (rows.next()) result.add(rosterEntry(rows));
                return List.copyOf(result);
            }
        }
    }

    private static List<ClanWarRosterEntry> readRosterForClan(Connection connection, UUID warId, UUID clanId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT war_id, clan_id, player_id, locked_at, released_at
                FROM clan_war_rosters
                WHERE war_id = ? AND clan_id = ? AND released_at IS NULL
                ORDER BY player_id
                """)) {
            statement.setObject(1, warId);
            statement.setObject(2, clanId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ClanWarRosterEntry> result = new ArrayList<>();
                while (rows.next()) result.add(rosterEntry(rows));
                return List.copyOf(result);
            }
        }
    }

    private static ClanWarRosterEntry rosterEntry(ResultSet row) throws SQLException {
        return new ClanWarRosterEntry(
                row.getObject("war_id", UUID.class),
                row.getObject("clan_id", UUID.class),
                row.getObject("player_id", UUID.class),
                row.getTimestamp("locked_at").toInstant(),
                nullableInstant(row.getTimestamp("released_at"))
        );
    }

    private static List<UUID> normalizedRoster(List<UUID> playerIds) {
        if (playerIds.isEmpty()) throw new IllegalArgumentException("roster must not be empty");
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID playerId : playerIds) unique.add(Objects.requireNonNull(playerId, "roster playerId"));
        if (unique.size() != playerIds.size()) throw new IllegalArgumentException("roster players must be unique");
        List<UUID> result = new ArrayList<>(unique);
        result.sort(Comparator.comparing(UUID::toString));
        return List.copyOf(result);
    }

    private static Optional<ProcessedOperation> findProcessed(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type, result::text AS result_json
                FROM processed_operations WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new ProcessedOperation(
                        row.getString("operation_type"), readJsonMap(row.getString("result_json"))
                ));
            }
        }
    }

    private static Object requireReplay(
            ProcessedOperation processed,
            String expectedType,
            Map<String, Object> request,
            UUID operationId
    ) {
        if (!expectedType.equals(processed.operationType())) {
            throw new ClanWarException("operation_id " + operationId + " already belongs to " + processed.operationType());
        }
        if (!objectMap(processed.data().get("request"), "request").equals(request)) {
            throw new ClanWarException("operation_id reused with a different clan-war request: " + operationId);
        }
        Object result = processed.data().get("result");
        if (result == null) throw new ClanWarException("processed clan-war operation is missing result");
        return result;
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            String operationType,
            Map<String, Object> request,
            Map<String, Object> result
    ) throws SQLException {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("request", request);
        body.put("result", result);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, writeJson(body));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requestMap(Object... fields) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            Object value = fields[index + 1];
            if (value instanceof List<?> list) {
                result.put(Objects.toString(fields[index]), List.copyOf(list));
            } else {
                result.put(Objects.toString(fields[index]), value == null ? null : Objects.toString(value));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, Object> warMap(ClanWarSnapshot war) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("war_id", war.warId().toString());
        value.put("challenger_clan_id", war.challengerClanId().toString());
        value.put("defender_clan_id", war.defenderClanId().toString());
        value.put("status", war.status().name());
        value.put("winning_clan_id", stringOrNull(war.winningClanId()));
        value.put("settlement_operation_id", stringOrNull(war.settlementOperationId()));
        value.put("resolution_operation_id", stringOrNull(war.resolutionOperationId()));
        value.put("challenged_by_player_id", stringOrNull(war.challengedByPlayerId()));
        value.put("accepted_by_player_id", stringOrNull(war.acceptedByPlayerId()));
        value.put("ruleset_id", war.rulesetId());
        value.put("ruleset_version", war.rulesetVersion());
        value.put("rating_policy_version", war.ratingPolicyVersion());
        value.put("rating_k_factor", war.ratingKFactor());
        value.put("team_size", war.teamSize());
        value.put("state_version", war.stateVersion());
        value.put("created_at", war.createdAt().toString());
        value.put("started_at", instantOrNull(war.startedAt()));
        value.put("finished_at", instantOrNull(war.finishedAt()));
        return value;
    }

    static ClanWarSnapshot warFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "war");
        return new ClanWarSnapshot(
                uuidValue(value, "war_id"),
                uuidValue(value, "challenger_clan_id"),
                uuidValue(value, "defender_clan_id"),
                ClanWarStatus.valueOf(stringValue(value, "status")),
                nullableUuid(value.get("winning_clan_id")),
                nullableUuid(value.get("settlement_operation_id")),
                nullableUuid(value.get("resolution_operation_id")),
                nullableUuid(value.get("challenged_by_player_id")),
                nullableUuid(value.get("accepted_by_player_id")),
                stringValue(value, "ruleset_id"),
                intValue(value, "ruleset_version"),
                intValue(value, "rating_policy_version"),
                intValue(value, "rating_k_factor"),
                intValue(value, "team_size"),
                longValue(value, "state_version"),
                Instant.parse(stringValue(value, "created_at")),
                nullableInstantValue(value.get("started_at")),
                nullableInstantValue(value.get("finished_at"))
        );
    }

    private static Map<String, Object> rosterMap(List<ClanWarRosterEntry> roster) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("roster", roster.stream().map(ClanWarLifecycleRepository::rosterEntryMap).toList());
        return value;
    }

    private static Map<String, Object> rosterEntryMap(ClanWarRosterEntry entry) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("war_id", entry.warId().toString());
        value.put("clan_id", entry.clanId().toString());
        value.put("player_id", entry.playerId().toString());
        value.put("locked_at", entry.lockedAt().toString());
        value.put("released_at", instantOrNull(entry.releasedAt()));
        return value;
    }

    private static List<ClanWarRosterEntry> rosterFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        Object rawRoster = value.get("roster");
        if (!(rawRoster instanceof List<?> list)) throw new ClanWarException("roster result is not a list");
        List<ClanWarRosterEntry> result = new ArrayList<>();
        for (Object element : list) {
            Map<String, Object> entry = objectMap(element, "roster_entry");
            result.add(new ClanWarRosterEntry(
                    uuidValue(entry, "war_id"),
                    uuidValue(entry, "clan_id"),
                    uuidValue(entry, "player_id"),
                    Instant.parse(stringValue(entry, "locked_at")),
                    nullableInstantValue(entry.get("released_at"))
            ));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ClanWarException("Could not parse clan-war idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ClanWarException("Could not serialize clan-war idempotency result", exception);
        }
    }

    static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) throw new ClanWarException("clan-war field is not an object: " + field);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) throw new ClanWarException("missing clan-war field: " + field);
        return Objects.toString(raw);
    }

    static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    static UUID nullableUuid(Object value) {
        return value == null ? null : UUID.fromString(Objects.toString(value));
    }

    static int intValue(Map<String, Object> value, String field) {
        return Math.toIntExact(longValue(value, field));
    }

    static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(Objects.toString(raw));
        } catch (RuntimeException exception) {
            throw new ClanWarException("invalid numeric clan-war field: " + field, exception);
        }
    }

    private static String stringOrNull(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String instantOrNull(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Instant nullableInstantValue(Object value) {
        return value == null ? null : Instant.parse(Objects.toString(value));
    }

    private static Instant nullableInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    static long increment(long current, String authority, Object id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new ClanWarException(authority + " state_version overflow: " + id, exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record ProcessedOperation(String operationType, Map<String, Object> data) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            data = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
        }
    }
}
