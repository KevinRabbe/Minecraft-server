package io.github.kevinrabbe.minecraftserver.paper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Session hint resolver for Paper resource interactions.
 *
 * <p>The returned session is intentionally not trusted by itself. ResourceSourceRepository re-locks and revalidates
 * backend ownership, instance ownership, lease validity, status, and state version inside the harvest transaction.</p>
 *
 * <p>Temporary bootstrap behavior: an otherwise-valid direct-login session may begin without owner_instance_id. When
 * this backend has exactly one ACTIVE zone instance, this resolver may atomically attach that unbound session to that
 * sole instance. It never rewrites an already-bound session or a TRANSFERRING session. A backend with zero or multiple
 * ACTIVE instances fails closed; the long-term scheduler/router must provide explicit instance ownership there.</p>
 */
final class PaperResourceSessionResolver {
    private final DataSource dataSource;
    private final String backendId;

    PaperResourceSessionResolver(DataSource dataSource, String backendId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        this.backendId = backendId.trim();
    }

    Optional<ResourceSessionHint> resolve(UUID minecraftUuid) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ResourceSessionHint> resolved = readLiveSession(connection, minecraftUuid, true);
                if (resolved.isEmpty()) {
                    connection.commit();
                    return Optional.empty();
                }

                ResourceSessionHint hint = resolved.orElseThrow();
                if (hint.instanceId() != null) {
                    connection.commit();
                    return Optional.of(hint.requireInstance());
                }

                Optional<UUID> soleActiveInstance = findSoleActiveInstance(connection);
                if (soleActiveInstance.isEmpty()) {
                    connection.commit();
                    return Optional.empty();
                }

                UUID instanceId = soleActiveInstance.orElseThrow();
                Optional<ResourceSessionHint> attached = attachUnboundSession(
                        connection,
                        hint.sessionId(),
                        hint.playerId(),
                        hint.stateVersion(),
                        instanceId
                );
                connection.commit();
                return attached.map(ResourceSessionHint::requireInstance);
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private Optional<ResourceSessionHint> readLiveSession(
            Connection connection,
            UUID minecraftUuid,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT s.network_session_id,
                       s.player_id,
                       s.owner_instance_id,
                       s.state_version
                FROM players p
                JOIN player_sessions s ON s.player_id = p.player_id
                WHERE p.minecraft_uuid = ?
                  AND s.owner_backend_id = ?
                  AND s.status IN ('ACTIVE', 'RECOVERING')
                  AND s.lease_expires_at IS NOT NULL
                  AND s.lease_expires_at > NOW()
                ORDER BY s.last_heartbeat_at DESC, s.network_session_id
                LIMIT 2
                """ + (forUpdate ? " FOR UPDATE OF s" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, minecraftUuid);
            statement.setString(2, backendId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                ResourceSessionHint hint = new ResourceSessionHint(
                        rows.getObject("network_session_id", UUID.class),
                        rows.getObject("player_id", UUID.class),
                        rows.getObject("owner_instance_id", UUID.class),
                        rows.getLong("state_version")
                );
                if (rows.next()) {
                    throw new IllegalStateException(
                            "More than one live resource-capable session resolved for Minecraft UUID " + minecraftUuid
                    );
                }
                return Optional.of(hint);
            }
        }
    }

    private Optional<UUID> findSoleActiveInstance(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT instance_id
                FROM zone_instances
                WHERE backend_id = ?
                  AND status = 'ACTIVE'
                ORDER BY instance_id
                LIMIT 2
                FOR SHARE
                """)) {
            statement.setString(1, backendId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                UUID instanceId = rows.getObject("instance_id", UUID.class);
                if (rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(instanceId);
            }
        }
    }

    private Optional<ResourceSessionHint> attachUnboundSession(
            Connection connection,
            UUID sessionId,
            UUID playerId,
            long stateVersion,
            UUID instanceId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE player_sessions
                SET owner_instance_id = ?,
                    last_heartbeat_at = NOW()
                WHERE network_session_id = ?
                  AND player_id = ?
                  AND owner_backend_id = ?
                  AND owner_instance_id IS NULL
                  AND state_version = ?
                  AND status IN ('ACTIVE', 'RECOVERING')
                  AND lease_expires_at IS NOT NULL
                  AND lease_expires_at > NOW()
                  AND EXISTS (
                      SELECT 1
                      FROM zone_instances z
                      WHERE z.instance_id = ?
                        AND z.backend_id = ?
                        AND z.status = 'ACTIVE'
                  )
                RETURNING owner_instance_id
                """)) {
            statement.setObject(1, instanceId);
            statement.setObject(2, sessionId);
            statement.setObject(3, playerId);
            statement.setString(4, backendId);
            statement.setLong(5, stateVersion);
            statement.setObject(6, instanceId);
            statement.setString(7, backendId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                UUID attachedInstance = row.getObject("owner_instance_id", UUID.class);
                if (row.next()) {
                    throw new IllegalStateException("Session attachment update returned multiple rows");
                }
                return Optional.of(new ResourceSessionHint(
                        sessionId,
                        playerId,
                        attachedInstance,
                        stateVersion
                ));
            }
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    record ResourceSessionHint(
            UUID sessionId,
            UUID playerId,
            UUID instanceId,
            long stateVersion
    ) {
        ResourceSessionHint {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            playerId = Objects.requireNonNull(playerId, "playerId");
            if (stateVersion < 0) {
                throw new IllegalArgumentException("stateVersion must be >= 0");
            }
        }

        ResourceSessionHint requireInstance() {
            if (instanceId == null) {
                throw new IllegalStateException("resource-capable session must have an owner instance");
            }
            return this;
        }
    }
}
