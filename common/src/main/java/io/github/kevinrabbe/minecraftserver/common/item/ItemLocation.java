package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;
import java.util.UUID;

/** One authoritative custody/location for an individual item. */
public record ItemLocation(ItemLocationKind kind, UUID locationId) {
    public ItemLocation {
        kind = Objects.requireNonNull(kind, "kind");
        if (requiresLocationId(kind) && locationId == null) {
            throw new IllegalArgumentException(kind + " requires a locationId");
        }
        if (!requiresLocationId(kind) && locationId != null) {
            throw new IllegalArgumentException(kind + " must not carry a locationId");
        }
    }

    public static ItemLocation playerInventory(UUID playerId) {
        return new ItemLocation(ItemLocationKind.PLAYER_INVENTORY, Objects.requireNonNull(playerId, "playerId"));
    }

    public static ItemLocation pendingDelivery(UUID deliveryId) {
        return new ItemLocation(
                ItemLocationKind.PENDING_DELIVERY,
                Objects.requireNonNull(deliveryId, "deliveryId")
        );
    }

    public static ItemLocation auctionEscrow(UUID listingId) {
        return new ItemLocation(
                ItemLocationKind.AUCTION_ESCROW,
                Objects.requireNonNull(listingId, "listingId")
        );
    }

    public static ItemLocation tradeEscrow(UUID tradeId) {
        return new ItemLocation(ItemLocationKind.TRADE_ESCROW, Objects.requireNonNull(tradeId, "tradeId"));
    }

    public static ItemLocation clanStorage(UUID clanId) {
        return new ItemLocation(ItemLocationKind.CLAN_STORAGE, Objects.requireNonNull(clanId, "clanId"));
    }

    public static ItemLocation warCustody(UUID warId) {
        return new ItemLocation(ItemLocationKind.WAR_CUSTODY, Objects.requireNonNull(warId, "warId"));
    }

    public static ItemLocation quarantine() {
        return new ItemLocation(ItemLocationKind.QUARANTINE, null);
    }

    public static ItemLocation destroyed() {
        return new ItemLocation(ItemLocationKind.DESTROYED, null);
    }

    private static boolean requiresLocationId(ItemLocationKind kind) {
        return switch (kind) {
            case PLAYER_INVENTORY, PENDING_DELIVERY, AUCTION_ESCROW, TRADE_ESCROW, CLAN_STORAGE, WAR_CUSTODY -> true;
            case QUARANTINE, DESTROYED -> false;
        };
    }
}
