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
 * Read-only hint resolver for Paper resource interactions.
 *
 * <p>The returned session is intentionally not trusted by itself. ResourceSourceRepository re-locks and revalidates
 * backend ownership, instance ownership, lease validity, status, and state version inside the harvest transaction.</p>
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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT s.network_session_id,
                            s.player_id,
                            s.owner_instance_id,
                            s.state_version
                     FROM players p
                     JOIN player_sessions s ON s.player_id = p.player_id
                     WHERE p.minecraft_uuid = ?
                       AND s.owner_backend_id = ?
                       AND s.owner_instance_id IS NOT NULL
                       AND s.status IN ('ACTIVE', 'RECOVERING')
                       AND s.lease_expires_at IS NOT NULL
                       AND s.lease_expires_at > NOW()
                     ORDER BY s.last_heartbeat_at DESC, s.network_session_id
                     LIMIT 2
                     """)) {
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

    record ResourceSessionHint(
            UUID sessionId,
            UUID playerId,
            UUID instanceId,
            long stateVersion
    ) {
        ResourceSessionHint {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            playerId = Objects.requireNonNull(playerId, "playerId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            if (stateVersion < 0) {
                throw new IllegalArgumentException("stateVersion must be >= 0");
            }
        }
    }
}
