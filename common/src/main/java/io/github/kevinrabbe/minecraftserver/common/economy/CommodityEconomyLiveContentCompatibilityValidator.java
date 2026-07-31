package io.github.kevinrabbe.minecraftserver.common.economy;

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

/** Startup compatibility gate for live generic-economy commodity custody and delivery obligations. */
public final class CommodityEconomyLiveContentCompatibilityValidator {
    private CommodityEconomyLiveContentCompatibilityValidator() { }

    public static void validate(DataSource dataSource, ItemCatalog itemCatalog) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(itemCatalog, "itemCatalog");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT source_kind, source_id, commodity_definition_id
                     FROM (
                         SELECT 'PENDING_DELIVERY' AS source_kind,
                                delivery_id::text AS source_id,
                                commodity_definition_id
                         FROM pending_commodity_deliveries
                         WHERE status = 'PENDING'

                         UNION ALL

                         SELECT 'BAZAAR_ORDER' AS source_kind,
                                order_id::text AS source_id,
                                commodity_definition_id
                         FROM bazaar_orders
                         WHERE status = 'OPEN'

                         UNION ALL

                         SELECT 'SECURE_TRADE_ESCROW' AS source_kind,
                                escrow.trade_id::text || ':' || escrow.owner_player_id::text AS source_id,
                                escrow.commodity_definition_id
                         FROM secure_trade_commodity_escrow escrow
                         JOIN secure_trades trade ON trade.trade_id = escrow.trade_id
                         WHERE trade.status IN ('OPEN', 'LOCKED')

                         UNION ALL

                         SELECT 'CLAN_STORAGE' AS source_kind,
                                clan_id::text AS source_id,
                                commodity_definition_id
                         FROM clan_commodity_balances
                         WHERE quantity > 0
                     ) live_commodity_dependencies
                     ORDER BY source_kind, source_id, commodity_definition_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String sourceKind = rows.getString("source_kind");
                String sourceId = rows.getString("source_id");
                String definitionId = rows.getString("commodity_definition_id");
                ItemDefinition definition = itemCatalog.find(definitionId).orElseThrow(() -> new ItemCatalogException(
                        "Loaded item catalog is missing commodity definition_id " + definitionId
                                + " required by live " + sourceKind + " " + sourceId
                ));
                if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                    throw new ItemCatalogException(
                            "Live " + sourceKind + " " + sourceId
                                    + " requires definition_id " + definitionId
                                    + " to remain COMMODITY, but loaded identity kind is "
                                    + definition.identityKind()
                    );
                }
            }
        }
    }
}
