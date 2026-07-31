package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogException;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Startup compatibility gate for positive player-owned bounty pouch balances. */
public final class BountyPouchLiveContentCompatibilityValidator {
    private BountyPouchLiveContentCompatibilityValidator() { }

    public static void validate(DataSource dataSource, ItemCatalog itemCatalog) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(itemCatalog, "itemCatalog");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id, family_id, commodity_definition_id
                     FROM bounty_pouch_balances
                     WHERE quantity > 0
                     ORDER BY player_id, family_id, commodity_definition_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID playerId = rows.getObject("player_id", UUID.class);
                String familyId = rows.getString("family_id");
                String definitionId = rows.getString("commodity_definition_id");
                ItemDefinition definition = itemCatalog.find(definitionId).orElseThrow(() -> new ItemCatalogException(
                        "Loaded item catalog is missing commodity definition_id " + definitionId
                                + " required by positive bounty pouch balance for player_id " + playerId
                                + " and family_id " + familyId
                ));
                if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                    throw new ItemCatalogException(
                            "Positive bounty pouch balance for player_id " + playerId
                                    + " and family_id " + familyId
                                    + " requires definition_id " + definitionId
                                    + " to remain COMMODITY, but loaded identity kind is "
                                    + definition.identityKind()
                    );
                }
            }
        }
    }
}
