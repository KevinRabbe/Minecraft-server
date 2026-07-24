package io.github.kevinrabbe.minecraftserver.common.economy;

import java.util.Objects;
import java.util.UUID;

public record AuctionPurchaseResult(
        UUID listingId,
        UUID sellerPlayerId,
        UUID buyerPlayerId,
        UUID itemInstanceId,
        String definitionId,
        UUID deliveryId,
        long itemStateVersion,
        long priceMinor,
        long buyerBalanceMinor,
        long buyerWalletVersion,
        long sellerBalanceMinor,
        long sellerWalletVersion
) {
    public AuctionPurchaseResult {
        listingId = Objects.requireNonNull(listingId, "listingId");
        sellerPlayerId = Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        buyerPlayerId = Objects.requireNonNull(buyerPlayerId, "buyerPlayerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (sellerPlayerId.equals(buyerPlayerId)) {
            throw new IllegalArgumentException("buyer and seller must differ");
        }
        if (itemStateVersion < 0 || priceMinor <= 0
                || buyerBalanceMinor < 0 || buyerWalletVersion < 0
                || sellerBalanceMinor < 0 || sellerWalletVersion < 0) {
            throw new IllegalArgumentException("invalid auction purchase amounts/versions");
        }
    }
}
