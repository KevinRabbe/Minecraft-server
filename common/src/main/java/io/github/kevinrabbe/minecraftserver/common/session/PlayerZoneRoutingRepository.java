package io.github.kevinrabbe.minecraftserver.common.session;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only routing projection of a player's durable logical location. Serialized MMO state is never selected. */
public final class PlayerZoneRoutingRepository {
    private final DataSource dataSource;

    public PlayerZoneRoutingRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Optional<String> findLogicalZone(UUID minecraftUuid) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT state.logical_zone_id
                     FROM players player
                     JOIN player_state state ON state.player_id = player.player_id
                     WHERE player.minecraft_uuid = ?
                       AND state.logical_zone_id IS NOT NULL
                       AND BTRIM(state.logical_zone_id) <> ''
                     """)) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                String logicalZoneId = row.getString("logical_zone_id");
                if (row.next()) {
                    throw new SessionConflictException(
                            "Minecraft UUID resolved to multiple durable logical locations: " + minecraftUuid
                    );
                }
                return Optional.of(logicalZoneId);
            }
        }
    }
}
