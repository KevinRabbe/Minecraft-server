package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record AuctionCancelResult(
        UUID listingId,
        UUID sellerPlayerId,
        UUID itemInstanceId,
        String definitionId,
        UUID deliveryId,
        long itemStateVersion
) {
    public AuctionCancelResult {
        listingId = Objects.requireNonNull(listingId, "listingId");
        sellerPlayerId = Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (itemStateVersion < 0) {
            throw new IllegalArgumentException("itemStateVersion must be >= 0");
        }
    }
}
