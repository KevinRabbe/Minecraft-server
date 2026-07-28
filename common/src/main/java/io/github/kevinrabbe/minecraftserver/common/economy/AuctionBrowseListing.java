package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.item.UpgradeState;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Read-only active-listing projection for player-facing Auction House discovery. */
public record AuctionBrowseListing(
        UUID listingId,
        UUID sellerPlayerId,
        UUID itemInstanceId,
        String definitionId,
        long priceMinor,
        Map<String, Integer> rollQualityBasisPoints,
        int upgradeLevel,
        Instant createdAt
) {
    public AuctionBrowseListing {
        listingId = Objects.requireNonNull(listingId, "listingId");
        sellerPlayerId = Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        itemInstanceId = Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        definitionId = definitionId.trim();
        if (priceMinor <= 0) {
            throw new IllegalArgumentException("priceMinor must be > 0");
        }
        rollQualityBasisPoints = Map.copyOf(Objects.requireNonNull(rollQualityBasisPoints, "rollQualityBasisPoints"));
        rollQualityBasisPoints.forEach((property, quality) -> {
            if (property == null || property.isBlank() || quality == null || quality < 0 || quality > 10_000) {
                throw new IllegalArgumentException("invalid normalized roll quality");
            }
        });
        new UpgradeState(upgradeLevel);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
