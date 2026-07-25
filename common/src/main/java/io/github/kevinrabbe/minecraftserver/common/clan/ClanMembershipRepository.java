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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Transactional authority for clan identity, invitations, membership and leadership. */
public final class ClanMembershipRepository {
    private static final String CREATE_OPERATION = "CLAN_CREATE";
    private static final String INVITE_OPERATION = "CLAN_INVITE";
    private static final String ACCEPT_OPERATION = "CLAN_INVITE_ACCEPT";
    private static final String CANCEL_INVITE_OPERATION = "CLAN_INVITE_CANCEL";
    private static final String LEAVE_OPERATION = "CLAN_LEAVE";
    private static final String REMOVE_OPERATION = "CLAN_MEMBER_REMOVE";
    private static final String TRANSFER_OPERATION = "CLAN_LEADERSHIP_TRANSFER";
    private static final Duration MAX_INVITE_LIFETIME = Duration.ofDays(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public ClanMembershipRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public ClanSnapshot loadClan(UUID clanId) throws SQLException {
        Objects.requireNonNull(clanId, "clanId");
        try (Connection connection = dataSource.getConnection()) {
            return readClan(connection, clanId, false);
        }
    }

    public ClanMemberSnapshot loadMember(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            return readMemberByPlayer(connection, playerId, false);
        }
    }

    public ClanInvitationSnapshot loadInvitation(UUID inviteId) throws SQLException {
        Objects.requireNonNull(inviteId, "inviteId");
        try (Connection connection = dataSource.getConnection()) {
            return readInvitation(connection, inviteId, false);
        }
    }

    public ClanSnapshot createClan(
            UUID operationId,
            UUID creatorPlayerId,
            String name,
            String tag
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(creatorPlayerId, "creatorPlayerId");
        String normalizedName = requireText(name, "name", 64);
        String normalizedTag = requireText(tag, "tag", 16).toUpperCase(Locale.ROOT);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), CREATE_OPERATION, operationId);
                    requireUuid(data, "creator_player_id", creatorPlayerId, operationId);
                    requireString(data, "name", normalizedName, operationId);
                    requireString(data, "tag", normalizedTag, operationId);
                    ClanSnapshot result = clanFrom(data.get("clan"));
                    connection.commit();
                    return result;
                }

                lockPlayer(connection, creatorPlayerId);
                requireNoMembership(connection, creatorPlayerId);
                UUID clanId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO clans(clan_id, name, tag, created_by_player_id, state_version)
                        VALUES (?, ?, ?, ?, 0)
                        """)) {
                    statement.setObject(1, clanId);
                    statement.setString(2, normalizedName);
                    statement.setString(3, normalizedTag);
                    statement.setObject(4, creatorPlayerId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO clan_members(clan_id, player_id, role)
                        VALUES (?, ?, 'LEADER')
                        """)) {
                    statement.setObject(1, clanId);
                    statement.setObject(2, creatorPlayerId);
                    statement.executeUpdate();
                }

                ClanSnapshot result = readClan(connection, clanId, false);
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("creator_player_id", creatorPlayerId.toString());
                data.put("name", normalizedName);
                data.put("tag", normalizedTag);
                data.put("clan", clanMap(result));
                insertProcessed(connection, operationId, CREATE_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanInvitationSnapshot invite(
            UUID operationId,
            UUID clanId,
            UUID actorPlayerId,
            UUID invitedPlayerId,
            Instant expiresAt
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(actorPlayerId, "actorPlayerId");
        Objects.requireNonNull(invitedPlayerId, "invitedPlayerId");
        Objects.requireNonNull(expiresAt, "expiresAt");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), INVITE_OPERATION, operationId);
                    requireUuid(data, "clan_id", clanId, operationId);
                    requireUuid(data, "actor_player_id", actorPlayerId, operationId);
                    requireUuid(data, "invited_player_id", invitedPlayerId, operationId);
                    requireString(data, "expires_at", expiresAt.toString(), operationId);
                    ClanInvitationSnapshot result = invitationFrom(data.get("invitation"));
                    connection.commit();
                    return result;
                }

                ClanSnapshot clan = readClan(connection, clanId, true);
                ClanMemberSnapshot actor = readMember(connection, clanId, actorPlayerId, true);
                if (actor.role() != ClanRole.LEADER && actor.role() != ClanRole.OFFICER) {
                    throw new ClanMembershipException("only LEADER or OFFICER may invite clan members");
                }
                requirePlayer(connection, invitedPlayerId);
                requireNoMembership(connection, invitedPlayerId);
                Instant now = databaseNow(connection);
                if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_INVITE_LIFETIME))) {
                    throw new ClanMembershipException("clan invitation expiry must be within the next 30 days");
                }
                expireStalePendingInvite(connection, clanId, invitedPlayerId, now);

                UUID inviteId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO clan_invitations(
                            invite_id, clan_id, invited_player_id, invited_by_player_id, status, expires_at
                        ) VALUES (?, ?, ?, ?, 'PENDING', ?)
                        """)) {
                    statement.setObject(1, inviteId);
                    statement.setObject(2, clanId);
                    statement.setObject(3, invitedPlayerId);
                    statement.setObject(4, actorPlayerId);
                    statement.setTimestamp(5, Timestamp.from(expiresAt));
                    statement.executeUpdate();
                }
                touchClan(connection, clan);
                ClanInvitationSnapshot result = readInvitation(connection, inviteId, false);
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("clan_id", clanId.toString());
                data.put("actor_player_id", actorPlayerId.toString());
                data.put("invited_player_id", invitedPlayerId.toString());
                data.put("expires_at", expiresAt.toString());
                data.put("invitation", invitationMap(result));
                insertProcessed(connection, operationId, INVITE_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanMemberSnapshot acceptInvite(
            UUID operationId,
            UUID inviteId,
            UUID invitedPlayerId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(inviteId, "inviteId");
        Objects.requireNonNull(invitedPlayerId, "invitedPlayerId");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), ACCEPT_OPERATION, operationId);
                    requireUuid(data, "invite_id", inviteId, operationId);
                    requireUuid(data, "invited_player_id", invitedPlayerId, operationId);
                    ClanMemberSnapshot result = memberFrom(data.get("member"));
                    connection.commit();
                    return result;
                }

                ClanInvitationSnapshot observed = readInvitation(connection, inviteId, false);
                if (!observed.invitedPlayerId().equals(invitedPlayerId)) {
                    throw new ClanMembershipException("clan invitation belongs to a different player");
                }
                lockPlayer(connection, invitedPlayerId);
                ClanSnapshot clan = readClan(connection, observed.clanId(), true);
                ClanInvitationSnapshot invitation = readInvitation(connection, inviteId, true);
                requireSameInvitationIdentity(observed, invitation);
                if (invitation.status() != ClanInvitationStatus.PENDING) {
                    throw new ClanMembershipException("clan invitation is not PENDING: " + inviteId);
                }
                Instant now = databaseNow(connection);
                if (!invitation.expiresAt().isAfter(now)) {
                    expireInvitation(connection, inviteId);
                    connection.commit();
                    throw new ClanMembershipException("clan invitation has expired: " + inviteId);
                }
                requireNoMembership(connection, invitedPlayerId);

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO clan_members(clan_id, player_id, role)
                        VALUES (?, ?, 'MEMBER')
                        """)) {
                    statement.setObject(1, clan.clanId());
                    statement.setObject(2, invitedPlayerId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE clan_invitations
                        SET status = 'ACCEPTED', accepted_at = NOW(), closed_at = NOW()
                        WHERE invite_id = ? AND status = 'PENDING' AND expires_at > NOW()
                        """)) {
                    statement.setObject(1, inviteId);
                    if (statement.executeUpdate() != 1) {
                        throw new ClanMembershipException("clan invitation changed concurrently while accepting");
                    }
                }
                touchClan(connection, clan);
                ClanMemberSnapshot result = readMember(connection, clan.clanId(), invitedPlayerId, false);
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("invite_id", inviteId.toString());
                data.put("invited_player_id", invitedPlayerId.toString());
                data.put("member", memberMap(result));
                insertProcessed(connection, operationId, ACCEPT_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanInvitationSnapshot cancelInvite(
            UUID operationId,
            UUID inviteId,
            UUID actorPlayerId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(inviteId, "inviteId");
        Objects.requireNonNull(actorPlayerId, "actorPlayerId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), CANCEL_INVITE_OPERATION, operationId);
                    requireUuid(data, "invite_id", inviteId, operationId);
                    requireUuid(data, "actor_player_id", actorPlayerId, operationId);
                    ClanInvitationSnapshot result = invitationFrom(data.get("invitation"));
                    connection.commit();
                    return result;
                }

                ClanInvitationSnapshot observed = readInvitation(connection, inviteId, false);
                ClanSnapshot clan = readClan(connection, observed.clanId(), true);
                ClanMemberSnapshot actor = readMember(connection, clan.clanId(), actorPlayerId, true);
                if (actor.role() != ClanRole.LEADER && actor.role() != ClanRole.OFFICER) {
                    throw new ClanMembershipException("only LEADER or OFFICER may cancel clan invitations");
                }
                ClanInvitationSnapshot invitation = readInvitation(connection, inviteId, true);
                requireSameInvitationIdentity(observed, invitation);
                if (invitation.status() != ClanInvitationStatus.PENDING) {
                    throw new ClanMembershipException("clan invitation is not PENDING: " + inviteId);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE clan_invitations
                        SET status = 'CANCELLED', closed_at = NOW()
                        WHERE invite_id = ? AND status = 'PENDING'
                        """)) {
                    statement.setObject(1, inviteId);
                    if (statement.executeUpdate() != 1) {
                        throw new ClanMembershipException("clan invitation changed concurrently while cancelling");
                    }
                }
                touchClan(connection, clan);
                ClanInvitationSnapshot result = readInvitation(connection, inviteId, false);
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("invite_id", inviteId.toString());
                data.put("actor_player_id", actorPlayerId.toString());
                data.put("invitation", invitationMap(result));
                insertProcessed(connection, operationId, CANCEL_INVITE_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanMembershipRemovalResult leaveClan(
            UUID operationId,
            UUID clanId,
            UUID playerId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), LEAVE_OPERATION, operationId);
                    requireUuid(data, "clan_id", clanId, operationId);
                    requireUuid(data, "player_id", playerId, operationId);
                    ClanMembershipRemovalResult result = removalFrom(data.get("result"));
                    connection.commit();
                    return result;
                }
                lockPlayer(connection, playerId);
                ClanSnapshot clan = readClan(connection, clanId, true);
                ClanMemberSnapshot member = readMember(connection, clanId, playerId, true);
                if (member.role() == ClanRole.LEADER) {
                    throw new ClanMembershipException("clan leader must transfer leadership before leaving");
                }
                deleteMember(connection, clanId, playerId);
                touchClan(connection, clan);
                ClanMembershipRemovalResult result = new ClanMembershipRemovalResult(clanId, playerId, member.role());
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("clan_id", clanId.toString());
                data.put("player_id", playerId.toString());
                data.put("result", removalMap(result));
                insertProcessed(connection, operationId, LEAVE_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanMembershipRemovalResult removeMember(
            UUID operationId,
            UUID clanId,
            UUID actorPlayerId,
            UUID targetPlayerId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(actorPlayerId, "actorPlayerId");
        Objects.requireNonNull(targetPlayerId, "targetPlayerId");
        if (actorPlayerId.equals(targetPlayerId)) {
            throw new IllegalArgumentException("use leaveClan for self-removal");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), REMOVE_OPERATION, operationId);
                    requireUuid(data, "clan_id", clanId, operationId);
                    requireUuid(data, "actor_player_id", actorPlayerId, operationId);
                    requireUuid(data, "target_player_id", targetPlayerId, operationId);
                    ClanMembershipRemovalResult result = removalFrom(data.get("result"));
                    connection.commit();
                    return result;
                }
                ClanSnapshot clan = readClan(connection, clanId, true);
                Map<UUID, ClanMemberSnapshot> locked = lockMembers(connection, clanId, actorPlayerId, targetPlayerId);
                ClanMemberSnapshot actor = locked.get(actorPlayerId);
                ClanMemberSnapshot target = locked.get(targetPlayerId);
                requireCanRemove(actor, target);
                lockPlayer(connection, targetPlayerId);
                deleteMember(connection, clanId, targetPlayerId);
                touchClan(connection, clan);
                ClanMembershipRemovalResult result = new ClanMembershipRemovalResult(
                        clanId, targetPlayerId, target.role()
                );
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("clan_id", clanId.toString());
                data.put("actor_player_id", actorPlayerId.toString());
                data.put("target_player_id", targetPlayerId.toString());
                data.put("result", removalMap(result));
                insertProcessed(connection, operationId, REMOVE_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public ClanLeadershipTransferResult transferLeadership(
            UUID operationId,
            UUID clanId,
            UUID currentLeaderPlayerId,
            UUID successorPlayerId,
            ClanRole formerLeaderRole
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(currentLeaderPlayerId, "currentLeaderPlayerId");
        Objects.requireNonNull(successorPlayerId, "successorPlayerId");
        Objects.requireNonNull(formerLeaderRole, "formerLeaderRole");
        if (currentLeaderPlayerId.equals(successorPlayerId)) {
            throw new IllegalArgumentException("successor must be a different member");
        }
        if (formerLeaderRole == ClanRole.LEADER) {
            throw new IllegalArgumentException("formerLeaderRole must be MEMBER or OFFICER");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), TRANSFER_OPERATION, operationId);
                    requireUuid(data, "clan_id", clanId, operationId);
                    requireUuid(data, "current_leader_player_id", currentLeaderPlayerId, operationId);
                    requireUuid(data, "successor_player_id", successorPlayerId, operationId);
                    requireString(data, "former_leader_role", formerLeaderRole.name(), operationId);
                    ClanLeadershipTransferResult result = transferFrom(data.get("result"));
                    connection.commit();
                    return result;
                }

                ClanSnapshot clan = readClan(connection, clanId, true);
                Map<UUID, ClanMemberSnapshot> locked = lockMembers(
                        connection, clanId, currentLeaderPlayerId, successorPlayerId
                );
                ClanMemberSnapshot currentLeader = locked.get(currentLeaderPlayerId);
                ClanMemberSnapshot successor = locked.get(successorPlayerId);
                if (currentLeader.role() != ClanRole.LEADER) {
                    throw new ClanMembershipException("currentLeaderPlayerId is not the clan leader");
                }
                if (successor.role() == ClanRole.LEADER) {
                    throw new ClanMembershipException("successor is already clan leader");
                }

                updateMemberRole(connection, clanId, currentLeaderPlayerId, formerLeaderRole);
                updateMemberRole(connection, clanId, successorPlayerId, ClanRole.LEADER);
                touchClan(connection, clan);
                ClanLeadershipTransferResult result = new ClanLeadershipTransferResult(
                        readMember(connection, clanId, successorPlayerId, false),
                        readMember(connection, clanId, currentLeaderPlayerId, false)
                );
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("clan_id", clanId.toString());
                data.put("current_leader_player_id", currentLeaderPlayerId.toString());
                data.put("successor_player_id", successorPlayerId.toString());
                data.put("former_leader_role", formerLeaderRole.name());
                data.put("result", transferMap(result));
                insertProcessed(connection, operationId, TRANSFER_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static void requireCanRemove(ClanMemberSnapshot actor, ClanMemberSnapshot target) {
        if (target.role() == ClanRole.LEADER) {
            throw new ClanMembershipException("clan leader cannot be removed directly");
        }
        if (actor.role() == ClanRole.LEADER) {
            return;
        }
        if (actor.role() == ClanRole.OFFICER && target.role() == ClanRole.MEMBER) {
            return;
        }
        throw new ClanMembershipException("actor role cannot remove target role");
    }

    private static void lockPlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ? FOR UPDATE")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanMembershipException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanMembershipException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static void requireNoMembership(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT clan_id FROM clan_members WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    throw new ClanMembershipException("player already belongs to clan " + row.getObject(1, UUID.class));
                }
            }
        }
    }

    private static ClanSnapshot readClan(Connection connection, UUID clanId, boolean forUpdate) throws SQLException {
        String sql = """
                SELECT name, tag, created_by_player_id, state_version, created_at, updated_at
                FROM clans
                WHERE clan_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanMembershipException("Unknown clan_id: " + clanId);
                }
                return new ClanSnapshot(
                        clanId,
                        row.getString("name"),
                        row.getString("tag"),
                        row.getObject("created_by_player_id", UUID.class),
                        row.getLong("state_version"),
                        row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant()
                );
            }
        }
    }

    private static ClanMemberSnapshot readMemberByPlayer(Connection connection, UUID playerId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT clan_id, role, joined_at
                FROM clan_members
                WHERE player_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanMembershipException("player is not a clan member: " + playerId);
                }
                return new ClanMemberSnapshot(
                        row.getObject("clan_id", UUID.class),
                        playerId,
                        ClanRole.valueOf(row.getString("role")),
                        row.getTimestamp("joined_at").toInstant()
                );
            }
        }
    }

    private static ClanMemberSnapshot readMember(
            Connection connection,
            UUID clanId,
            UUID playerId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT role, joined_at
                FROM clan_members
                WHERE clan_id = ? AND player_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanMembershipException("player is not a member of clan " + clanId + ": " + playerId);
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

    private static Map<UUID, ClanMemberSnapshot> lockMembers(
            Connection connection,
            UUID clanId,
            UUID first,
            UUID second
    ) throws SQLException {
        List<UUID> ids = new ArrayList<>(List.of(first, second));
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
            throw new ClanMembershipException("both players must be members of clan " + clanId);
        }
        return Map.copyOf(result);
    }

    private static ClanInvitationSnapshot readInvitation(Connection connection, UUID inviteId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT clan_id, invited_player_id, invited_by_player_id, status,
                       created_at, expires_at, accepted_at, closed_at
                FROM clan_invitations
                WHERE invite_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, inviteId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanMembershipException("Unknown clan invitation: " + inviteId);
                }
                Timestamp acceptedAt = row.getTimestamp("accepted_at");
                Timestamp closedAt = row.getTimestamp("closed_at");
                return new ClanInvitationSnapshot(
                        inviteId,
                        row.getObject("clan_id", UUID.class),
                        row.getObject("invited_player_id", UUID.class),
                        row.getObject("invited_by_player_id", UUID.class),
                        ClanInvitationStatus.valueOf(row.getString("status")),
                        row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant(),
                        acceptedAt == null ? null : acceptedAt.toInstant(),
                        closedAt == null ? null : closedAt.toInstant()
                );
            }
        }
    }

    private static void requireSameInvitationIdentity(
            ClanInvitationSnapshot observed,
            ClanInvitationSnapshot locked
    ) {
        if (!observed.inviteId().equals(locked.inviteId())
                || !observed.clanId().equals(locked.clanId())
                || !observed.invitedPlayerId().equals(locked.invitedPlayerId())
                || !observed.invitedByPlayerId().equals(locked.invitedByPlayerId())
                || !observed.createdAt().equals(locked.createdAt())
                || !observed.expiresAt().equals(locked.expiresAt())) {
            throw new ClanMembershipException("clan invitation identity changed concurrently");
        }
    }

    private static void expireStalePendingInvite(
            Connection connection,
            UUID clanId,
            UUID invitedPlayerId,
            Instant now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE clan_invitations
                SET status = 'EXPIRED', closed_at = NOW()
                WHERE clan_id = ?
                  AND invited_player_id = ?
                  AND status = 'PENDING'
                  AND expires_at <= ?
                """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, invitedPlayerId);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private static void expireInvitation(Connection connection, UUID inviteId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE clan_invitations
                SET status = 'EXPIRED', closed_at = NOW()
                WHERE invite_id = ? AND status = 'PENDING'
                """)) {
            statement.setObject(1, inviteId);
            if (statement.executeUpdate() != 1) {
                throw new ClanMembershipException("clan invitation changed concurrently while expiring");
            }
        }
    }

    private static void deleteMember(Connection connection, UUID clanId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM clan_members WHERE clan_id = ? AND player_id = ?
                """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            if (statement.executeUpdate() != 1) {
                throw new ClanMembershipException("clan membership changed concurrently");
            }
        }
    }

    private static void updateMemberRole(
            Connection connection,
            UUID clanId,
            UUID playerId,
            ClanRole role
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE clan_members SET role = ? WHERE clan_id = ? AND player_id = ?
                """)) {
            statement.setString(1, role.name());
            statement.setObject(2, clanId);
            statement.setObject(3, playerId);
            if (statement.executeUpdate() != 1) {
                throw new ClanMembershipException("clan membership role changed concurrently");
            }
        }
    }

    private static ClanSnapshot touchClan(Connection connection, ClanSnapshot clan) throws SQLException {
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
                RETURNING name, tag, created_by_player_id, created_at, updated_at
                """)) {
            statement.setLong(1, nextVersion);
            statement.setObject(2, clan.clanId());
            statement.setLong(3, clan.stateVersion());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanMembershipException("clan changed concurrently: " + clan.clanId());
                }
                return new ClanSnapshot(
                        clan.clanId(),
                        row.getString("name"),
                        row.getString("tag"),
                        row.getObject("created_by_player_id", UUID.class),
                        nextVersion,
                        row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant()
                );
            }
        }
    }

    private static Instant databaseNow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT NOW()")) {
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
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
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ProcessedOperation(
                        row.getString("operation_type"),
                        readJsonMap(row.getString("result_json"))
                ));
            }
        }
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            String operationType,
            Map<String, Object> result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, writeJson(result));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requireType(
            ProcessedOperation operation,
            String expectedType,
            UUID operationId
    ) {
        if (!expectedType.equals(operation.operationType())) {
            throw new ClanMembershipException(
                    "operation_id " + operationId + " already belongs to " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static Map<String, Object> clanMap(ClanSnapshot clan) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("clan_id", clan.clanId().toString());
        value.put("name", clan.name());
        value.put("tag", clan.tag());
        value.put("created_by_player_id", clan.createdByPlayerId().toString());
        value.put("state_version", clan.stateVersion());
        value.put("created_at", clan.createdAt().toString());
        value.put("updated_at", clan.updatedAt().toString());
        return value;
    }

    private static ClanSnapshot clanFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "clan");
        return new ClanSnapshot(
                uuidValue(value, "clan_id"),
                stringValue(value, "name"),
                stringValue(value, "tag"),
                uuidValue(value, "created_by_player_id"),
                longValue(value, "state_version"),
                Instant.parse(stringValue(value, "created_at")),
                Instant.parse(stringValue(value, "updated_at"))
        );
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

    private static Map<String, Object> invitationMap(ClanInvitationSnapshot invitation) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("invite_id", invitation.inviteId().toString());
        value.put("clan_id", invitation.clanId().toString());
        value.put("invited_player_id", invitation.invitedPlayerId().toString());
        value.put("invited_by_player_id", invitation.invitedByPlayerId().toString());
        value.put("status", invitation.status().name());
        value.put("created_at", invitation.createdAt().toString());
        value.put("expires_at", invitation.expiresAt().toString());
        value.put("accepted_at", invitation.acceptedAt() == null ? null : invitation.acceptedAt().toString());
        value.put("closed_at", invitation.closedAt() == null ? null : invitation.closedAt().toString());
        return value;
    }

    private static ClanInvitationSnapshot invitationFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "invitation");
        String acceptedAt = nullableString(value, "accepted_at");
        String closedAt = nullableString(value, "closed_at");
        return new ClanInvitationSnapshot(
                uuidValue(value, "invite_id"),
                uuidValue(value, "clan_id"),
                uuidValue(value, "invited_player_id"),
                uuidValue(value, "invited_by_player_id"),
                ClanInvitationStatus.valueOf(stringValue(value, "status")),
                Instant.parse(stringValue(value, "created_at")),
                Instant.parse(stringValue(value, "expires_at")),
                acceptedAt == null ? null : Instant.parse(acceptedAt),
                closedAt == null ? null : Instant.parse(closedAt)
        );
    }

    private static Map<String, Object> removalMap(ClanMembershipRemovalResult result) {
        return Map.of(
                "clan_id", result.clanId().toString(),
                "player_id", result.playerId().toString(),
                "former_role", result.formerRole().name()
        );
    }

    private static ClanMembershipRemovalResult removalFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "removal");
        return new ClanMembershipRemovalResult(
                uuidValue(value, "clan_id"),
                uuidValue(value, "player_id"),
                ClanRole.valueOf(stringValue(value, "former_role"))
        );
    }

    private static Map<String, Object> transferMap(ClanLeadershipTransferResult result) {
        return Map.of(
                "new_leader", memberMap(result.newLeader()),
                "former_leader", memberMap(result.formerLeader())
        );
    }

    private static ClanLeadershipTransferResult transferFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "transfer");
        return new ClanLeadershipTransferResult(
                memberFrom(value.get("new_leader")),
                memberFrom(value.get("former_leader"))
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ClanMembershipException("Could not parse clan idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ClanMembershipException("Could not serialize clan idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ClanMembershipException("clan idempotency field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new ClanMembershipException("clan idempotency result is missing field: " + field);
        }
        return Objects.toString(raw);
    }

    private static String nullableString(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw == null ? null : Objects.toString(raw);
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new ClanMembershipException("clan idempotency field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!uuidValue(data, field).equals(expected)) {
            throw reused(operationId);
        }
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) {
            throw reused(operationId);
        }
    }

    private static ClanMembershipException reused(UUID operationId) {
        return new ClanMembershipException("operation_id reused with a different clan request: " + operationId);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(result, "result")));
        }
    }
}
