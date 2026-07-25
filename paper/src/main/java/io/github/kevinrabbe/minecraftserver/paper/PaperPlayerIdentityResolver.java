package io.github.kevinrabbe.minecraftserver.paper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only mapping from Minecraft account UUID to the network's stable opaque player identity. */
final class PaperPlayerIdentityResolver {
    private final DataSource dataSource;

    PaperPlayerIdentityResolver(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    Optional<UUID> resolve(UUID minecraftUuid) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id
                     FROM players
                     WHERE minecraft_uuid = ?
                     """)) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                UUID playerId = row.getObject("player_id", UUID.class);
                if (row.next()) {
                    throw new IllegalStateException("Minecraft UUID resolved to multiple persistent player identities");
                }
                return Optional.of(playerId);
            }
        }
    }
}
