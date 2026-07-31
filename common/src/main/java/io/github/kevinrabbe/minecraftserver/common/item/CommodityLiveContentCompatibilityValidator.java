package io.github.kevinrabbe.minecraftserver.common.item;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Startup compatibility gate for fungible value retained by SQL-native live/recoverable authorities. */
public final class CommodityLiveContentCompatibilityValidator {
    private CommodityLiveContentCompatibilityValidator() { }

    public static void validate(DataSource dataSource, ItemCatalog catalog) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(catalog, "catalog");

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT authority_kind,
                           authority_id,
                           commodity_definition_id
                    FROM (
                        SELECT 'PENDING_DELIVERY' AS authority_kind,
                               delivery_id::text AS authority_id,
                               commodity_definition_id
                        FROM pending_commodity_deliveries
                        WHERE status = 'PENDING'

                        UNION ALL

                        SELECT 'BAZAAR_ORDER',
                               order_id::text,
                               commodity_definition_id
                        FROM bazaar_orders
                        WHERE status = 'OPEN'

                        UNION ALL

                        SELECT 'SECURE_TRADE_ESCROW',
                               escrow.trade_id::text,
                               escrow.commodity_definition_id
                        FROM secure_trade_commodity_escrow escrow
                        JOIN secure_trades trade ON trade.trade_id = escrow.trade_id
                        WHERE trade.status IN ('OPEN', 'LOCKED')

                        UNION ALL

                        SELECT 'CRAFTING_COMMISSION_ESCROW',
                               materials.commission_id::text,
                               materials.commodity_definition_id
                        FROM crafting_commission_materials materials
                        JOIN crafting_commissions commission
                          ON commission.commission_id = materials.commission_id
                        WHERE commission.status IN ('OPEN', 'ACCEPTED')

                        UNION ALL

                        SELECT 'CLAN_STORAGE',
                               balance.clan_id::text,
                               balance.commodity_definition_id
                        FROM clan_commodity_balances balance
                        WHERE balance.quantity > 0

                        UNION ALL

                        SELECT 'BOUNTY_POUCH',
                               balance.player_id::text || '/' || balance.family_id,
                               balance.commodity_definition_id
                        FROM bounty_pouch_balances balance
                        WHERE balance.quantity > 0

                        UNION ALL

                        SELECT 'RESOURCE_HARVEST',
                               harvest.harvest_id::text,
                               harvest.commodity_definition_id
                        FROM resource_harvests harvest
                        LEFT JOIN resource_harvest_fulfillments fulfillment
                          ON fulfillment.harvest_id = harvest.harvest_id
                        WHERE fulfillment.harvest_id IS NULL
                    ) live_commodity_authority
                    ORDER BY authority_kind, authority_id, commodity_definition_id
                    """)) {
                statement.setFetchSize(64);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String authorityKind = rows.getString("authority_kind");
                        String authorityId = rows.getString("authority_id");
                        String definitionId = rows.getString("commodity_definition_id");
                        ItemDefinition definition = catalog.find(definitionId).orElseThrow(() -> new ItemCatalogException(
                                "Loaded item catalog is missing live commodity definition_id " + definitionId
                                        + " required by " + authorityKind + ':' + authorityId
                        ));
                        if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                            throw new ItemCatalogException(
                                    "Live fungible authority " + authorityKind + ':' + authorityId
                                            + " requires definition_id " + definitionId
                                            + " to remain COMMODITY, but loaded identity kind is "
                                            + definition.identityKind()
                            );
                        }
                    }
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
