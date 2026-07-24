package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record AuctionListingCreateResult(
        UUID listingId,
        UUID sellerPlayerId,
        UUID itemInstanceId,
        String definitionId,
        long escrowItemVersion,
        long priceMinor,
        long playerStateVersion
) {
    public AuctionListingCreateResult {
        listingId = Objects.requireNonNull(listingId, "listingId");
        sellerPlayerId = Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (escrowItemVersion < 0 || priceMinor <= 0 || playerStateVersion < 0) {
            throw new IllegalArgumentException("versions must be >= 0 and priceMinor must be > 0");
        }
    }
}
