package io.github.kevinrabbe.minecraftserver.common.item;

import java.util.Objects;
import java.util.UUID;

/** One authoritative custody/location for an individual item. */
public record ItemLocation(ItemLocationKind kind, UUID locationId) {
    public ItemLocation {
        kind = Objects.requireNonNull(kind, "kind");
        if ((kind == ItemLocationKind.PLAYER_INVENTORY
                || kind == ItemLocationKind.PENDING_DELIVERY
                || kind == ItemLocationKind.AUCTION_ESCROW)
                && locationId == null) {
            throw new IllegalArgumentException(kind + " requires a locationId");
        }
        if ((kind == ItemLocationKind.QUARANTINE || kind == ItemLocationKind.DESTROYED)
                && locationId != null) {
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

    public static ItemLocation quarantine() {
        return new ItemLocation(ItemLocationKind.QUARANTINE, null);
    }

    public static ItemLocation destroyed() {
        return new ItemLocation(ItemLocationKind.DESTROYED, null);
    }
}
