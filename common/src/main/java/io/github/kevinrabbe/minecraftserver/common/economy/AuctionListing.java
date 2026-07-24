package io.github.kevinrabbe.minecraftserver.common.economy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuctionListing(
        UUID listingId,
        UUID sellerPlayerId,
        UUID itemInstanceId,
        long escrowItemVersion,
        long priceMinor,
        AuctionListingStatus status,
        UUID createOperationId,
        UUID settleOperationId,
        UUID buyerPlayerId,
        UUID settlementDeliveryId,
        Instant createdAt,
        Instant settledAt
) {
    public AuctionListing {
        listingId = Objects.requireNonNull(listingId, "listingId");
        sellerPlayerId = Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (escrowItemVersion < 0) {
            throw new IllegalArgumentException("escrowItemVersion must be >= 0");
        }
        if (priceMinor <= 0) {
            throw new IllegalArgumentException("priceMinor must be > 0");
        }
        status = Objects.requireNonNull(status, "status");
        createOperationId = Objects.requireNonNull(createOperationId, "createOperationId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");

        if (status == AuctionListingStatus.ACTIVE
                && (settleOperationId != null || buyerPlayerId != null || settlementDeliveryId != null || settledAt != null)) {
            throw new IllegalArgumentException("ACTIVE listing must not contain settlement metadata");
        }
        if (status == AuctionListingStatus.SOLD
                && (settleOperationId == null || buyerPlayerId == null || settlementDeliveryId == null || settledAt == null)) {
            throw new IllegalArgumentException("SOLD listing requires settlement metadata and buyer");
        }
        if (status == AuctionListingStatus.CANCELLED
                && (settleOperationId == null || buyerPlayerId != null || settlementDeliveryId == null || settledAt == null)) {
            throw new IllegalArgumentException("CANCELLED listing requires settlement metadata without buyer");
        }
    }
}
