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

/** Read-only bounded reconciliation of Auction House lifecycle evidence across listings, provenance, deliveries and ledgers. */
public final class AuctionIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String CREATE = "AUCTION_LISTING_CREATE";
    private static final String PURCHASE = "AUCTION_LISTING_PURCHASE";
    private static final String CANCEL = "AUCTION_LISTING_CANCEL";

    private final DataSource dataSource;

    public AuctionIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyCreateEvidence(connection, issues, maxIssues);
            verifySettlementEvidence(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyCreateEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT listing.listing_id
                FROM auction_listings listing
                LEFT JOIN item_instances item ON item.item_instance_id = listing.item_instance_id
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = listing.create_operation_id
                WHERE item.item_instance_id IS NULL
                   OR operation.operation_id IS NULL
                   OR operation.operation_type IS DISTINCT FROM ?
                   OR operation.result ->> 'listing_id' IS DISTINCT FROM listing.listing_id::TEXT
                   OR operation.result ->> 'seller_player_id' IS DISTINCT FROM listing.seller_player_id::TEXT
                   OR operation.result ->> 'item_instance_id' IS DISTINCT FROM listing.item_instance_id::TEXT
                   OR operation.result ->> 'definition_id' IS DISTINCT FROM item.definition_id
                   OR operation.result ->> 'escrow_item_version' IS DISTINCT FROM listing.escrow_item_version::TEXT
                   OR operation.result ->> 'price_minor' IS DISTINCT FROM listing.price_minor::TEXT
                   OR operation.result ->> 'reason' IS NULL
                   OR operation.result ->> 'reason' = ''
                   OR (
                        SELECT COUNT(*)
                        FROM item_provenance provenance
                        WHERE provenance.item_instance_id = listing.item_instance_id
                          AND provenance.sequence_no = listing.escrow_item_version
                          AND provenance.operation_id = listing.create_operation_id
                          AND provenance.event_type = 'MOVED'
                          AND provenance.from_location_kind = 'PLAYER_INVENTORY'
                          AND provenance.from_location_id = listing.seller_player_id
                          AND provenance.to_location_kind = 'AUCTION_ESCROW'
                          AND provenance.to_location_id = listing.listing_id
                          AND provenance.reason = operation.result ->> 'reason'
                          AND provenance.actor_player_id = listing.seller_player_id
                      ) <> 1
                   OR (
                        SELECT COUNT(*)
                        FROM economic_ledger ledger
                        WHERE ledger.operation_id = listing.create_operation_id
                          AND ledger.player_id = listing.seller_player_id
                          AND ledger.asset_type = 'ITEM_INSTANCE'
                          AND ledger.asset_id = listing.item_instance_id::TEXT
                          AND ledger.amount = 1
                          AND ledger.direction = 'DEBIT'
                          AND ledger.reason = operation.result ->> 'reason'
                      ) <> 1
                   OR (
                        SELECT COUNT(*)
                        FROM economic_ledger ledger
                        WHERE ledger.operation_id = listing.create_operation_id
                      ) <> 1
                ORDER BY listing.listing_id
                LIMIT ?
                """)) {
            statement.setString(1, CREATE);
            statement.setInt(2, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID listingId = rows.getObject("listing_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "AUCTION_CREATE_EVIDENCE_MISMATCH",
                            listingId.toString(),
                            "Auction listing creation does not reconcile with its processed operation, item provenance and escrow ledger evidence"
                    ));
                }
            }
        }
    }

    private static void verifySettlementEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT listing.listing_id
                FROM auction_listings listing
                LEFT JOIN item_instances item ON item.item_instance_id = listing.item_instance_id
                LEFT JOIN processed_operations operation
                  ON operation.operation_id = listing.settle_operation_id
                LEFT JOIN pending_unique_deliveries delivery
                  ON delivery.delivery_id = listing.settlement_delivery_id
                WHERE (
                        listing.status = 'ACTIVE'
                        AND (
                             listing.settle_operation_id IS NOT NULL
                          OR listing.buyer_player_id IS NOT NULL
                          OR listing.settlement_delivery_id IS NOT NULL
                          OR listing.settled_at IS NOT NULL
                        )
                      )
                   OR (
                        listing.status = 'SOLD'
                        AND (
                             item.item_instance_id IS NULL
                          OR operation.operation_id IS NULL
                          OR operation.operation_type IS DISTINCT FROM ?
                          OR operation.result ->> 'listing_id' IS DISTINCT FROM listing.listing_id::TEXT
                          OR operation.result ->> 'seller_player_id' IS DISTINCT FROM listing.seller_player_id::TEXT
                          OR operation.result ->> 'buyer_player_id' IS DISTINCT FROM listing.buyer_player_id::TEXT
                          OR operation.result ->> 'item_instance_id' IS DISTINCT FROM listing.item_instance_id::TEXT
                          OR operation.result ->> 'definition_id' IS DISTINCT FROM item.definition_id
                          OR operation.result ->> 'delivery_id' IS DISTINCT FROM listing.settlement_delivery_id::TEXT
                          OR operation.result ->> 'item_state_version'
                                IS DISTINCT FROM (listing.escrow_item_version + 1)::TEXT
                          OR operation.result ->> 'price_minor' IS DISTINCT FROM listing.price_minor::TEXT
                          OR operation.result ->> 'reason' IS NULL
                          OR operation.result ->> 'reason' = ''
                          OR delivery.delivery_id IS NULL
                          OR delivery.recipient_player_id IS DISTINCT FROM listing.buyer_player_id
                          OR delivery.item_instance_id IS DISTINCT FROM listing.item_instance_id
                          OR delivery.issue_operation_id IS DISTINCT FROM listing.settle_operation_id
                          OR delivery.issue_reason IS DISTINCT FROM operation.result ->> 'reason'
                          OR (
                              SELECT COUNT(*)
                              FROM item_provenance provenance
                              WHERE provenance.item_instance_id = listing.item_instance_id
                                AND provenance.sequence_no = listing.escrow_item_version + 1
                                AND provenance.operation_id = listing.settle_operation_id
                                AND provenance.event_type = 'MOVED'
                                AND provenance.from_location_kind = 'AUCTION_ESCROW'
                                AND provenance.from_location_id = listing.listing_id
                                AND provenance.to_location_kind = 'PENDING_DELIVERY'
                                AND provenance.to_location_id = listing.settlement_delivery_id
                                AND provenance.reason = operation.result ->> 'reason'
                                AND provenance.actor_player_id = listing.buyer_player_id
                          ) <> 1
                          OR (
                              SELECT COUNT(*)
                              FROM economic_ledger ledger
                              WHERE ledger.operation_id = listing.settle_operation_id
                                AND ledger.player_id = listing.buyer_player_id
                                AND ledger.asset_type = 'CURRENCY'
                                AND ledger.asset_id = 'coin'
                                AND ledger.amount = listing.price_minor
                                AND ledger.direction = 'DEBIT'
                                AND ledger.reason = operation.result ->> 'reason'
                          ) <> 1
                          OR (
                              SELECT COUNT(*)
                              FROM economic_ledger ledger
                              WHERE ledger.operation_id = listing.settle_operation_id
                                AND ledger.player_id = listing.seller_player_id
                                AND ledger.asset_type = 'CURRENCY'
                                AND ledger.asset_id = 'coin'
                                AND ledger.amount = listing.price_minor
                                AND ledger.direction = 'CREDIT'
                                AND ledger.reason = operation.result ->> 'reason'
                          ) <> 1
                          OR (
                              SELECT COUNT(*)
                              FROM economic_ledger ledger
                              WHERE ledger.operation_id = listing.settle_operation_id
                                AND ledger.player_id = listing.buyer_player_id
                                AND ledger.asset_type = 'ITEM_INSTANCE'
                                AND ledger.asset_id = listing.item_instance_id::TEXT
                                AND ledger.amount = 1
                                AND ledger.direction = 'CREDIT'
                                AND ledger.reason = operation.result ->> 'reason'
                          ) <> 1
                          OR (
                              SELECT COUNT(*)
                              FROM economic_ledger ledger
                              WHERE ledger.operation_id = listing.settle_operation_id
                          ) <> 3
                        )
                      )
                   OR (
                        listing.status = 'CANCELLED'
                        AND (
                             item.item_instance_id IS NULL
                          OR operation.operation_id IS NULL
                          OR operation.operation_type IS DISTINCT FROM ?
                          OR operation.result ->> 'listing_id' IS DISTINCT FROM listing.listing_id::TEXT
                          OR operation.result ->> 'seller_player_id' IS DISTINCT FROM listing.seller_player_id::TEXT
                          OR operation.result ->> 'item_instance_id' IS DISTINCT FROM listing.item_instance_id::TEXT
                          OR operation.result ->> 'definition_id' IS DISTINCT FROM item.definition_id
                          OR operation.result ->> 'delivery_id' IS DISTINCT FROM listing.settlement_delivery_id::TEXT
                          OR operation.result ->> 'item_state_version'
                                IS DISTINCT FROM (listing.escrow_item_version + 1)::TEXT
                          OR operation.result ->> 'reason' IS NULL
                          OR operation.result ->> 'reason' = ''
                          OR delivery.delivery_id IS NULL
                          OR delivery.recipient_player_id IS DISTINCT FROM listing.seller_player_id
                          OR delivery.item_instance_id IS DISTINCT FROM listing.item_instance_id
                          OR delivery.issue_operation_id IS DISTINCT FROM listing.settle_operation_id
                          OR delivery.issue_reason IS DISTINCT FROM operation.result ->> 'reason'
                          OR (
                              SELECT COUNT(*)
                              FROM item_provenance provenance
                              WHERE provenance.item_instance_id = listing.item_instance_id
                                AND provenance.sequence_no = listing.escrow_item_version + 1
                                AND provenance.operation_id = listing.settle_operation_id
                                AND provenance.event_type = 'MOVED'
                                AND provenance.from_location_kind = 'AUCTION_ESCROW'
                                AND provenance.from_location_id = listing.listing_id
                                AND provenance.to_location_kind = 'PENDING_DELIVERY'
                                AND provenance.to_location_id = listing.settlement_delivery_id
                                AND provenance.reason = operation.result ->> 'reason'
                                AND provenance.actor_player_id = listing.seller_player_id
                          ) <> 1
                          OR (
                              SELECT COUNT(*)
                              FROM economic_ledger ledger
                              WHERE ledger.operation_id = listing.settle_operation_id
                                AND ledger.player_id = listing.seller_player_id
                                AND ledger.asset_type = 'ITEM_INSTANCE'
                                AND ledger.asset_id = listing.item_instance_id::TEXT
                                AND ledger.amount = 1
                                AND ledger.direction = 'CREDIT'
                                AND ledger.reason = operation.result ->> 'reason'
                          ) <> 1
                          OR (
                              SELECT COUNT(*)
                              FROM economic_ledger ledger
                              WHERE ledger.operation_id = listing.settle_operation_id
                          ) <> 1
                        )
                      )
                   OR listing.status NOT IN ('ACTIVE', 'SOLD', 'CANCELLED')
                ORDER BY listing.listing_id
                LIMIT ?
                """)) {
            statement.setString(1, PURCHASE);
            statement.setString(2, CANCEL);
            statement.setInt(3, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID listingId = rows.getObject("listing_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "AUCTION_SETTLEMENT_EVIDENCE_MISMATCH",
                            listingId.toString(),
                            "Auction listing settlement does not reconcile with processed operation, pending delivery, provenance and exact ledger evidence"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
