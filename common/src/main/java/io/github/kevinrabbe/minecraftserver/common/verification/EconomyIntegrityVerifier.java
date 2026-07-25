package io.github.kevinrabbe.minecraftserver.common.verification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only bounded verification across authoritative economy/custody tables. */
public final class EconomyIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public EconomyIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyCoinConservation(connection, issues, maxIssues);
            verifyPendingUniqueCustody(connection, issues, maxIssues);
            verifyAuctionCustody(connection, issues, maxIssues);
            verifyTradeCustody(connection, issues, maxIssues);
            verifyClanCommodityCustody(connection, issues, maxIssues);
            verifyClanUniqueCustody(connection, issues, maxIssues);
            verifySalvageEvidence(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyCoinConservation(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH ledger_net AS (
                    SELECT player_id,
                           COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) AS net_minor
                    FROM economic_ledger
                    WHERE player_id IS NOT NULL
                      AND asset_type = 'CURRENCY'
                      AND asset_id = 'coin'
                    GROUP BY player_id
                ),
                holdings AS (
                    SELECT player_id, SUM(amount_minor) AS holdings_minor
                    FROM (
                        SELECT player_id, balance_minor AS amount_minor FROM wallets
                        UNION ALL
                        SELECT player_id, balance_minor FROM bank_accounts
                    ) owned_coin
                    GROUP BY player_id
                )
                SELECT p.player_id,
                       COALESCE(h.holdings_minor, 0) AS holdings_minor,
                       COALESCE(l.net_minor, 0) AS ledger_net_minor
                FROM players p
                LEFT JOIN holdings h ON h.player_id = p.player_id
                LEFT JOIN ledger_net l ON l.player_id = p.player_id
                WHERE COALESCE(h.holdings_minor, 0) <> COALESCE(l.net_minor, 0)
                ORDER BY p.player_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID playerId = rows.getObject("player_id", UUID.class);
                    long holdings = rows.getLong("holdings_minor");
                    long ledger = rows.getLong("ledger_net_minor");
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "COIN_HOLDINGS_LEDGER_MISMATCH",
                            playerId.toString(),
                            "Wallet plus protected-bank Coin " + holdings + " does not match ledger net " + ledger
                    ));
                }
            }
        }
    }

    private static void verifyPendingUniqueCustody(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT d.delivery_id,
                       d.status,
                       d.recipient_player_id,
                       d.item_instance_id,
                       i.location_kind,
                       i.location_id
                FROM pending_unique_deliveries d
                LEFT JOIN item_instances i ON i.item_instance_id = d.item_instance_id
                WHERE i.item_instance_id IS NULL
                   OR (d.status = 'PENDING' AND (
                        i.location_kind IS DISTINCT FROM 'PENDING_DELIVERY'
                        OR i.location_id IS DISTINCT FROM d.delivery_id
                   ))
                   OR (d.status = 'CLAIMED' AND (
                        i.location_kind IS DISTINCT FROM 'PLAYER_INVENTORY'
                        OR i.location_id IS DISTINCT FROM d.recipient_player_id
                   ))
                ORDER BY d.created_at, d.delivery_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID deliveryId = rows.getObject("delivery_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "PENDING_UNIQUE_CUSTODY_MISMATCH",
                            deliveryId.toString(),
                            "Pending unique delivery status does not match authoritative item custody"
                    ));
                }
            }
        }
    }

    private static void verifyAuctionCustody(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT l.listing_id
                FROM auction_listings l
                LEFT JOIN item_instances i ON i.item_instance_id = l.item_instance_id
                WHERE l.status = 'ACTIVE'
                  AND (
                      i.item_instance_id IS NULL
                      OR i.location_kind IS DISTINCT FROM 'AUCTION_ESCROW'
                      OR i.location_id IS DISTINCT FROM l.listing_id
                      OR i.state_version IS DISTINCT FROM l.escrow_item_version
                  )
                ORDER BY l.created_at, l.listing_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID listingId = rows.getObject("listing_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "AUCTION_CUSTODY_MISMATCH",
                            listingId.toString(),
                            "Active Auction listing does not match unique-item escrow authority"
                    ));
                }
            }
        }
    }

    private static void verifyTradeCustody(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.trade_id, s.item_instance_id
                FROM secure_trade_unique_items s
                JOIN secure_trades t ON t.trade_id = s.trade_id
                LEFT JOIN item_instances i ON i.item_instance_id = s.item_instance_id
                WHERE t.status IN ('OPEN', 'LOCKED')
                  AND (
                      i.item_instance_id IS NULL
                      OR i.location_kind IS DISTINCT FROM 'TRADE_ESCROW'
                      OR i.location_id IS DISTINCT FROM s.trade_id
                      OR i.state_version IS DISTINCT FROM s.escrow_item_version
                  )
                ORDER BY s.trade_id, s.item_instance_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID tradeId = rows.getObject("trade_id", UUID.class);
                    UUID itemId = rows.getObject("item_instance_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "TRADE_CUSTODY_MISMATCH",
                            tradeId + ":" + itemId,
                            "Open/locked secure-trade item does not match unique-item escrow authority"
                    ));
                }
            }
        }
    }

    private static void verifyClanCommodityCustody(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH clan_ledger AS (
                    SELECT related_entity_id AS clan_id_text,
                           asset_id AS commodity_definition_id,
                           COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) AS ledger_net
                    FROM economic_ledger
                    WHERE player_id IS NULL
                      AND asset_type = 'COMMODITY'
                      AND related_entity_id IN (SELECT clan_id::text FROM clans)
                    GROUP BY related_entity_id, asset_id
                )
                SELECT COALESCE(b.clan_id::text, l.clan_id_text) AS clan_id_text,
                       COALESCE(b.commodity_definition_id, l.commodity_definition_id) AS commodity_definition_id,
                       COALESCE(b.quantity, 0) AS stored_quantity,
                       COALESCE(l.ledger_net, 0) AS ledger_net
                FROM clan_commodity_balances b
                FULL OUTER JOIN clan_ledger l
                  ON l.clan_id_text = b.clan_id::text
                 AND l.commodity_definition_id = b.commodity_definition_id
                WHERE COALESCE(b.quantity, 0) <> COALESCE(l.ledger_net, 0)
                ORDER BY 1, 2
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String clanId = rows.getString("clan_id_text");
                    String commodity = rows.getString("commodity_definition_id");
                    long stored = rows.getLong("stored_quantity");
                    long ledger = rows.getLong("ledger_net");
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_COMMODITY_LEDGER_MISMATCH",
                            clanId + ":" + commodity,
                            "Clan commodity quantity " + stored + " does not match clan ledger net " + ledger
                    ));
                }
            }
        }
    }

    private static void verifyClanUniqueCustody(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH clan_ledger AS (
                    SELECT related_entity_id AS clan_id_text,
                           asset_id AS item_id_text,
                           COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) AS ledger_net
                    FROM economic_ledger
                    WHERE player_id IS NULL
                      AND asset_type = 'ITEM_INSTANCE'
                      AND related_entity_id IN (SELECT clan_id::text FROM clans)
                    GROUP BY related_entity_id, asset_id
                ),
                holdings AS (
                    SELECT location_id::text AS clan_id_text,
                           item_instance_id::text AS item_id_text,
                           1::bigint AS held
                    FROM item_instances
                    WHERE location_kind = 'CLAN_STORAGE'
                )
                SELECT COALESCE(h.clan_id_text, l.clan_id_text) AS clan_id_text,
                       COALESCE(h.item_id_text, l.item_id_text) AS item_id_text,
                       COALESCE(h.held, 0) AS held,
                       COALESCE(l.ledger_net, 0) AS ledger_net
                FROM holdings h
                FULL OUTER JOIN clan_ledger l
                  ON l.clan_id_text = h.clan_id_text
                 AND l.item_id_text = h.item_id_text
                WHERE COALESCE(h.held, 0) <> COALESCE(l.ledger_net, 0)
                ORDER BY 1, 2
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String clanId = rows.getString("clan_id_text");
                    String itemId = rows.getString("item_id_text");
                    long held = rows.getLong("held");
                    long ledger = rows.getLong("ledger_net");
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "CLAN_UNIQUE_CUSTODY_LEDGER_MISMATCH",
                            clanId + ":" + itemId,
                            "Clan unique-item custody " + held + " does not match clan ledger net " + ledger
                    ));
                }
            }
        }
    }

    private static void verifySalvageEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.salvage_id
                FROM salvage_records s
                LEFT JOIN item_instances i ON i.item_instance_id = s.item_instance_id
                LEFT JOIN item_provenance p
                  ON p.item_instance_id = s.item_instance_id
                 AND p.sequence_no = s.destroyed_item_version
                 AND p.operation_id = s.operation_id
                 AND p.to_location_kind = 'DESTROYED'
                 AND p.to_location_id IS NULL
                WHERE i.item_instance_id IS NULL
                   OR i.definition_id IS DISTINCT FROM s.item_definition_id
                   OR i.location_kind IS DISTINCT FROM 'DESTROYED'
                   OR i.state_version IS DISTINCT FROM s.destroyed_item_version
                   OR p.item_instance_id IS NULL
                ORDER BY s.created_at, s.salvage_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID salvageId = rows.getObject("salvage_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "SALVAGE_EVIDENCE_MISMATCH",
                            salvageId.toString(),
                            "Salvage record does not reconcile to terminal item/provenance evidence"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
