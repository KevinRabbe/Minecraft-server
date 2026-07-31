package io.github.kevinrabbe.minecraftserver.common.pve.map;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Startup compatibility gate for pending durable Map reward grants. */
public final class MapRewardLiveContentCompatibilityValidator {
    private MapRewardLiveContentCompatibilityValidator() { }

    /**
     * Verifies that every pending reward grant can still materialize into its ordinary delivery authority.
     * Display, material, and other balance tuning may change behind stable definition IDs.
     * Fulfilled grants no longer pin content here because custody has moved to delivery/item authorities.
     */
    public static void validate(DataSource dataSource, ItemCatalog itemCatalog) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(itemCatalog, "itemCatalog");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT grant_id,
                            run_id,
                            player_id,
                            reward_kind,
                            definition_id
                     FROM map_reward_grants
                     WHERE status = 'PENDING'
                     ORDER BY created_at, run_id, ordinal, grant_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID grantId = rows.getObject("grant_id", UUID.class);
                UUID runId = rows.getObject("run_id", UUID.class);
                UUID playerId = rows.getObject("player_id", UUID.class);
                MapRewardKind kind = MapRewardKind.valueOf(rows.getString("reward_kind"));
                String definitionId = rows.getString("definition_id");

                ItemDefinition definition;
                try {
                    definition = itemCatalog.require(definitionId);
                } catch (RuntimeException exception) {
                    throw new MapAuthorityException(
                            "Loaded item catalog is missing definition_id " + definitionId
                                    + " required by pending " + kind + " Map reward grant " + grantId
                                    + " for run " + runId + " and player_id " + playerId,
                            exception
                    );
                }

                ItemIdentityKind expected = kind == MapRewardKind.COMMODITY
                        ? ItemIdentityKind.COMMODITY
                        : ItemIdentityKind.INDIVIDUAL;
                if (definition.identityKind() != expected) {
                    throw new MapAuthorityException(
                            "Pending " + kind + " Map reward grant " + grantId
                                    + " requires definition_id " + definitionId
                                    + " to remain " + expected
                                    + ", but loaded identity kind is " + definition.identityKind()
                    );
                }
            }
        }
    }
}
